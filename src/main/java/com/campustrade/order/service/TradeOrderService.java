package com.campustrade.order.service;

import com.campustrade.common.exception.BizException;
import com.campustrade.common.tx.TransactionHooks;
import com.campustrade.common.web.AuthenticatedUser;
import com.campustrade.item.model.Item;
import com.campustrade.item.model.ItemStatus;
import com.campustrade.item.service.ItemService;
import com.campustrade.notification.service.NotificationOutboxService;
import com.campustrade.order.dto.CancelOrderRequest;
import com.campustrade.order.dto.CreateAppointmentRequest;
import com.campustrade.order.dto.OrderResponse;
import com.campustrade.order.model.OrderStatus;
import com.campustrade.order.model.TradeOrder;
import com.campustrade.order.repository.TradeOrderRepository;
import com.campustrade.order.timeout.OrderTimeoutQueue;
import com.campustrade.risk.idempotency.IdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

@Service
public class TradeOrderService {

    private static final Logger log = LoggerFactory.getLogger(TradeOrderService.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final TradeOrderRepository orderRepository;
    private final ItemService itemService;
    private final NotificationOutboxService notificationOutboxService;
    private final OrderStateMachine stateMachine;
    private final OrderTimeoutQueue orderTimeoutQueue;
    private final IdempotencyService idempotencyService;
    private final long timeoutHours;
    private final int timeoutBatchSize;
    private final long timeoutRetryDelaySeconds;
    private final TransactionTemplate transactionTemplate;

    public TradeOrderService(
            TradeOrderRepository orderRepository,
            ItemService itemService,
            NotificationOutboxService notificationOutboxService,
            OrderStateMachine stateMachine,
            OrderTimeoutQueue orderTimeoutQueue,
            IdempotencyService idempotencyService,
            ObjectProvider<PlatformTransactionManager> transactionManagerProvider,
            @Value("${app.order-timeout.hours:24}") long timeoutHours,
            @Value("${app.order-timeout.batch-size:50}") int timeoutBatchSize,
            @Value("${app.order-timeout.retry-delay-seconds:60}") long timeoutRetryDelaySeconds
    ) {
        this.orderRepository = orderRepository;
        this.itemService = itemService;
        this.notificationOutboxService = notificationOutboxService;
        this.stateMachine = stateMachine;
        this.orderTimeoutQueue = orderTimeoutQueue;
        this.idempotencyService = idempotencyService;
        this.timeoutHours = timeoutHours;
        this.timeoutBatchSize = timeoutBatchSize;
        this.timeoutRetryDelaySeconds = timeoutRetryDelaySeconds;
        PlatformTransactionManager transactionManager = transactionManagerProvider.getIfAvailable();
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    @Transactional
    public OrderResponse createAppointment(
            AuthenticatedUser buyer,
            Long itemId,
            CreateAppointmentRequest request,
            String idempotencyKey
    ) {
        Item item = itemService.requireItem(itemId);
        if (item.getSellerId().equals(buyer.id())) {
            throw BizException.badRequest("You cannot appoint your own item");
        }
        if (item.getStatus() != ItemStatus.ON_SALE) {
            throw BizException.conflict("Current item is not available for appointment");
        }

        idempotencyService.guardAppointmentSubmit(
                buyer.id(),
                itemId,
                idempotencyKey,
                request.expectedTime() + ":" + request.note()
        );

        TradeOrder order = new TradeOrder();
        order.setOrderNo(generateOrderNo());
        order.setItemId(itemId);
        order.setBuyerId(buyer.id());
        order.setSellerId(item.getSellerId());
        order.setStatus(OrderStatus.PENDING);
        order.setExpectedTime(request.expectedTime());
        order.setNote(request.note());
        order.setExpireAt(LocalDateTime.now().plusHours(timeoutHours));
        orderRepository.save(order);

        TransactionHooks.afterCommit(() -> orderTimeoutQueue.enqueue(order.getId(), order.getExpireAt()));

        notificationOutboxService.enqueue(
                item.getSellerId(),
                "APPOINTMENT_CREATED",
                "New appointment request",
                "User " + buyer.username() + " appointed your item: " + item.getTitle(),
                order.getId()
        );
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse confirm(AuthenticatedUser seller, Long orderId) {
        TradeOrder order = requireOrder(orderId);
        requireSeller(seller.id(), order);
        stateMachine.assertCanTransit(order.getStatus(), OrderStatus.CONFIRMED);

        boolean reserved = itemService.reserveIfOnSale(order.getItemId());
        if (!reserved) {
            throw BizException.conflict("Item has already been reserved, sold, or removed");
        }

        boolean updated = orderRepository.updateStatusIfCurrent(
                orderId,
                OrderStatus.PENDING,
                OrderStatus.CONFIRMED,
                target -> target.setConfirmedAt(LocalDateTime.now())
        );
        if (!updated) {
            throw BizException.conflict("Order status changed before confirmation");
        }

        TransactionHooks.afterCommit(() -> orderTimeoutQueue.remove(orderId));

        TradeOrder latest = requireOrder(orderId);
        notificationOutboxService.enqueue(
                latest.getBuyerId(),
                "APPOINTMENT_CONFIRMED",
                "Appointment confirmed",
                "Seller confirmed your appointment, please trade offline on time",
                latest.getId()
        );
        return OrderResponse.from(latest);
    }

    @Transactional
    public OrderResponse reject(AuthenticatedUser seller, Long orderId) {
        TradeOrder order = requireOrder(orderId);
        requireSeller(seller.id(), order);
        stateMachine.assertCanTransit(order.getStatus(), OrderStatus.REJECTED);

        boolean updated = orderRepository.updateStatusIfCurrent(orderId, OrderStatus.PENDING, OrderStatus.REJECTED, null);
        if (!updated) {
            throw BizException.conflict("Order status changed before rejection");
        }

        TransactionHooks.afterCommit(() -> orderTimeoutQueue.remove(orderId));

        TradeOrder latest = requireOrder(orderId);
        notificationOutboxService.enqueue(
                latest.getBuyerId(),
                "APPOINTMENT_REJECTED",
                "Appointment rejected",
                "Seller rejected your appointment, you can continue browsing other items",
                latest.getId()
        );
        return OrderResponse.from(latest);
    }

    @Transactional
    public OrderResponse cancel(AuthenticatedUser user, Long orderId, CancelOrderRequest request) {
        TradeOrder order = requireOrder(orderId);
        requireParticipant(user.id(), order);

        if (order.getStatus() == OrderStatus.PENDING) {
            stateMachine.assertCanTransit(order.getStatus(), OrderStatus.CANCELLED);
            updateOrderStatus(orderId, OrderStatus.PENDING, OrderStatus.CANCELLED, request.reason());
        } else if (order.getStatus() == OrderStatus.CONFIRMED) {
            stateMachine.assertCanTransit(order.getStatus(), OrderStatus.CANCELLED);
            updateOrderStatus(orderId, OrderStatus.CONFIRMED, OrderStatus.CANCELLED, request.reason());
            itemService.restoreOnSaleIfReserved(order.getItemId());
        } else {
            throw BizException.conflict("Current order status cannot be cancelled");
        }

        TransactionHooks.afterCommit(() -> orderTimeoutQueue.remove(orderId));

        TradeOrder latest = requireOrder(orderId);
        Long receiverId = user.id().equals(latest.getBuyerId()) ? latest.getSellerId() : latest.getBuyerId();
        notificationOutboxService.enqueue(
                receiverId,
                "ORDER_CANCELLED",
                "Appointment cancelled",
                "Order " + latest.getOrderNo() + " has been cancelled",
                latest.getId()
        );
        return OrderResponse.from(latest);
    }

    @Transactional
    public OrderResponse complete(AuthenticatedUser user, Long orderId) {
        TradeOrder order = requireOrder(orderId);
        requireParticipant(user.id(), order);
        stateMachine.assertCanTransit(order.getStatus(), OrderStatus.COMPLETED);

        itemService.markSoldIfReserved(order.getItemId());
        boolean updated = orderRepository.updateStatusIfCurrent(
                orderId,
                OrderStatus.CONFIRMED,
                OrderStatus.COMPLETED,
                target -> target.setCompletedAt(LocalDateTime.now())
        );
        if (!updated) {
            throw BizException.conflict("Order status changed before completion");
        }

        TransactionHooks.afterCommit(() -> orderTimeoutQueue.remove(orderId));

        TradeOrder latest = requireOrder(orderId);
        notificationOutboxService.enqueue(
                latest.getBuyerId(),
                "ORDER_COMPLETED",
                "Order completed",
                "Trade completed, do not forget to leave a review",
                latest.getId()
        );
        notificationOutboxService.enqueue(
                latest.getSellerId(),
                "ORDER_COMPLETED",
                "Order completed",
                "Trade completed, do not forget to leave a review",
                latest.getId()
        );
        return OrderResponse.from(latest);
    }

    public int expireDueOrders() {
        List<Long> dueOrderIds = orderTimeoutQueue.dueOrderIds(LocalDateTime.now(), timeoutBatchSize);
        int count = 0;
        for (Long orderId : dueOrderIds) {
            try {
                if (Boolean.TRUE.equals(runInTransaction(() -> expireOrderIfPending(orderId)))) {
                    count++;
                }
            } catch (BizException ignored) {
                // Order may already be handled by user action or another concurrent update.
            } catch (Exception exception) {
                requeueExpiredOrder(orderId);
                log.warn("Failed to expire order {}, requeued for retry", orderId, exception);
            }
        }
        return count;
    }

    public List<OrderResponse> myBuyOrders(Long buyerId, int page, int size) {
        return orderRepository.findByBuyerId(buyerId, safePage(page), safeSize(size)).stream()
                .map(OrderResponse::from)
                .toList();
    }

    public List<OrderResponse> mySellOrders(Long sellerId, int page, int size) {
        return orderRepository.findBySellerId(sellerId, safePage(page), safeSize(size)).stream()
                .map(OrderResponse::from)
                .toList();
    }

    public TradeOrder requireOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> BizException.notFound("Order does not exist"));
    }

    private boolean expireOrderIfPending(Long orderId) {
        TradeOrder order = requireOrder(orderId);
        if (order.getStatus() != OrderStatus.PENDING) {
            TransactionHooks.afterCommit(() -> orderTimeoutQueue.remove(orderId));
            return false;
        }

        stateMachine.assertCanTransit(order.getStatus(), OrderStatus.EXPIRED);
        boolean updated = orderRepository.updateStatusIfCurrent(order.getId(), OrderStatus.PENDING, OrderStatus.EXPIRED, null);
        if (!updated) {
            TransactionHooks.afterCommit(() -> orderTimeoutQueue.remove(orderId));
            return false;
        }

        TransactionHooks.afterCommit(() -> orderTimeoutQueue.remove(orderId));

        notificationOutboxService.enqueue(
                order.getBuyerId(),
                "ORDER_EXPIRED",
                "Appointment expired",
                "Seller did not confirm in time, the appointment was auto-cancelled",
                order.getId()
        );
        notificationOutboxService.enqueue(
                order.getSellerId(),
                "ORDER_EXPIRED",
                "Appointment expired",
                "You did not process the appointment in time, the system cancelled it automatically",
                order.getId()
        );
        return true;
    }

    private void requeueExpiredOrder(Long orderId) {
        orderTimeoutQueue.enqueue(orderId, LocalDateTime.now().plusSeconds(timeoutRetryDelaySeconds));
    }

    private <T> T runInTransaction(Supplier<T> supplier) {
        if (transactionTemplate == null) {
            return supplier.get();
        }
        return transactionTemplate.execute(status -> supplier.get());
    }

    private void updateOrderStatus(Long orderId, OrderStatus expected, OrderStatus next, String cancelReason) {
        boolean updated = orderRepository.updateStatusIfCurrent(
                orderId,
                expected,
                next,
                target -> target.setCancelReason(cancelReason)
        );
        if (!updated) {
            throw BizException.conflict("Order status changed before current operation");
        }
    }

    private void requireSeller(Long userId, TradeOrder order) {
        if (!order.getSellerId().equals(userId)) {
            throw BizException.forbidden("Only seller can perform this action");
        }
    }

    private void requireParticipant(Long userId, TradeOrder order) {
        if (!order.getSellerId().equals(userId) && !order.getBuyerId().equals(userId)) {
            throw BizException.forbidden("Only order participants can perform this action");
        }
    }

    private int safePage(int page) {
        return Math.max(1, page);
    }

    private int safeSize(int size) {
        return Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    }

    private String generateOrderNo() {
        return "CT" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + ThreadLocalRandom.current().nextInt(1000, 9999);
    }
}
