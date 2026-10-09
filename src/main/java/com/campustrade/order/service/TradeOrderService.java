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
import com.campustrade.order.timeout.OrderTimeoutClaim;
import com.campustrade.observability.OrderTimeoutMetrics;
import com.campustrade.risk.idempotency.IdempotencyService;
import com.campustrade.risk.idempotency.AppointmentIdempotencyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
    private final AppointmentIdempotencyRepository appointmentIdempotencyRepository;
    private final Object inMemoryTradeMonitor = new Object();
    private final long timeoutHours;
    private final int timeoutBatchSize;
    private final long timeoutRetryDelaySeconds;
    private final Duration timeoutLease;
    private final OrderTimeoutMetrics timeoutMetrics;
    private LocalDateTime fallbackCursorExpireAt;
    private Long fallbackCursorId;
    private final TransactionTemplate transactionTemplate;

    public TradeOrderService(
            TradeOrderRepository orderRepository,
            ItemService itemService,
            NotificationOutboxService notificationOutboxService,
            OrderStateMachine stateMachine,
            OrderTimeoutQueue orderTimeoutQueue,
            IdempotencyService idempotencyService,
            AppointmentIdempotencyRepository appointmentIdempotencyRepository,
            ObjectProvider<PlatformTransactionManager> transactionManagerProvider,
            @Value("${app.order-timeout.hours:24}") long timeoutHours,
            @Value("${app.order-timeout.batch-size:50}") int timeoutBatchSize,
            @Value("${app.order-timeout.retry-delay-seconds:60}") long timeoutRetryDelaySeconds,
            @Value("${app.order-timeout.lease-seconds:120}") long timeoutLeaseSeconds,
            OrderTimeoutMetrics timeoutMetrics
    ) {
        this.orderRepository = orderRepository;
        this.itemService = itemService;
        this.notificationOutboxService = notificationOutboxService;
        this.stateMachine = stateMachine;
        this.orderTimeoutQueue = orderTimeoutQueue;
        this.idempotencyService = idempotencyService;
        this.appointmentIdempotencyRepository = appointmentIdempotencyRepository;
        this.timeoutHours = timeoutHours;
        this.timeoutBatchSize = timeoutBatchSize;
        this.timeoutRetryDelaySeconds = timeoutRetryDelaySeconds;
        this.timeoutLease = Duration.ofSeconds(Math.max(1, timeoutLeaseSeconds));
        this.timeoutMetrics = timeoutMetrics;
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
        return executeTrade(() -> {
            String key = idempotencyService.normalizeExplicitKey(idempotencyKey);
            if (key != null) {
                return appointmentIdempotencyRepository.execute(buyer.id(), key,
                        idempotencyService.appointmentRequestHash(itemId, request),
                        () -> createNewAppointment(buyer, itemId, request, false));
            }
            return createNewAppointment(buyer, itemId, request, true);
        });
    }

    private OrderResponse createNewAppointment(AuthenticatedUser buyer, Long itemId,
                                               CreateAppointmentRequest request, boolean guardFingerprint) {
        // Validate only a new operation: a successful keyed request remains replayable later.
        if (request.expectedTime() == null || !request.expectedTime().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            throw BizException.badRequest("预约交易时间必须晚于当前时间");
        }
        Item item = itemService.requireItem(itemId);
        if (item.getSellerId().equals(buyer.id())) {
            throw BizException.badRequest("You cannot appoint your own item");
        }
        if (item.getStatus() != ItemStatus.ON_SALE) {
            throw BizException.conflict("Current item is not available for appointment");
        }

        if (guardFingerprint) {
            idempotencyService.guardAppointmentSubmit(buyer.id(), itemId, null,
                    idempotencyService.appointmentRequestHash(itemId, request));
        }

        TradeOrder order = new TradeOrder();
        order.setOrderNo(generateOrderNo());
        order.setItemId(itemId);
        order.setBuyerId(buyer.id());
        order.setSellerId(item.getSellerId());
        order.setStatus(OrderStatus.PENDING);
        order.setExpectedTime(request.expectedTime());
        order.setNote(request.note());
        order.setExpireAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(timeoutHours));
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
        return executeTrade(() -> confirmAppointment(seller, orderId));
    }

    private OrderResponse confirmAppointment(AuthenticatedUser seller, Long orderId) {
        TradeOrder order = requireOrder(orderId);
        requireSeller(seller.id(), order);
        stateMachine.assertCanTransit(order.getStatus(), OrderStatus.CONFIRMED);
        if (transactionTemplate == null
                && (order.getExpireAt() == null || !order.getExpireAt().isAfter(LocalDateTime.now(ZoneOffset.UTC)))) {
            throw BizException.conflict("预约已到期，无法确认");
        }

        boolean reserved = itemService.reserveIfOnSale(order.getItemId(), orderId);
        if (!reserved) {
            throw BizException.conflict("Item has already been reserved, sold, or removed");
        }

        boolean updated = orderRepository.confirmIfPendingAndNotExpired(
                orderId,
                target -> target.setConfirmedAt(LocalDateTime.now(ZoneOffset.UTC))
        );
        if (!updated) {
            if (transactionTemplate == null) {
                itemService.restoreOnSaleIfReserved(order.getItemId(), orderId);
            }
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
        return executeTrade(() -> rejectAppointment(seller, orderId));
    }

    private OrderResponse rejectAppointment(AuthenticatedUser seller, Long orderId) {
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
        return executeTrade(() -> cancelAppointment(user, orderId, request));
    }

    private OrderResponse cancelAppointment(AuthenticatedUser user, Long orderId, CancelOrderRequest request) {
        TradeOrder order = requireOrder(orderId);
        requireParticipant(user.id(), order);

        if (order.getStatus() == OrderStatus.PENDING) {
            stateMachine.assertCanTransit(order.getStatus(), OrderStatus.CANCELLED);
            updateOrderStatus(orderId, OrderStatus.PENDING, OrderStatus.CANCELLED, request.reason());
        } else if (order.getStatus() == OrderStatus.CONFIRMED) {
            stateMachine.assertCanTransit(order.getStatus(), OrderStatus.CANCELLED);
            // Match confirm/complete lock order: item first, then order. SQL failure rolls both back.
            itemService.restoreOnSaleIfReserved(order.getItemId(), orderId);
            updateOrderStatus(orderId, OrderStatus.CONFIRMED, OrderStatus.CANCELLED, request.reason());
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
        return executeTrade(() -> completeAppointment(user, orderId));
    }

    private OrderResponse completeAppointment(AuthenticatedUser user, Long orderId) {
        TradeOrder order = requireOrder(orderId);
        requireParticipant(user.id(), order);
        stateMachine.assertCanTransit(order.getStatus(), OrderStatus.COMPLETED);

        itemService.markSoldIfReserved(order.getItemId(), orderId);
        boolean updated = orderRepository.updateStatusIfCurrent(
                orderId,
                OrderStatus.CONFIRMED,
                OrderStatus.COMPLETED,
                target -> target.setCompletedAt(LocalDateTime.now(ZoneOffset.UTC))
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
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<OrderTimeoutClaim> claims;
        try {
            claims = orderTimeoutQueue.claimDue(now, timeoutBatchSize, timeoutLease);
            timeoutMetrics.claimed(claims.size());
        } catch (Exception exception) {
            timeoutMetrics.backendFailure();
            log.warn("Timeout queue unavailable, processing a bounded database batch", exception);
            return expireDueOrdersFromDatabase(now);
        }
        int count = 0;
        for (OrderTimeoutClaim claim : claims) {
            try {
                LocalDateTime expireAt = orderRepository.findById(claim.orderId())
                        .map(TradeOrder::getExpireAt).orElse(null);
                boolean expired = Boolean.TRUE.equals(runInTransaction(() -> expireOrderIfPending(claim.orderId())));
                if (expired) {
                    count++;
                    recordExpiration(expireAt);
                } else {
                    TradeOrder latest = orderRepository.findById(claim.orderId()).orElse(null);
                    if (latest != null && latest.getStatus() == OrderStatus.PENDING) {
                        LocalDateTime retryAt = latest.getExpireAt().isAfter(now) ? latest.getExpireAt()
                                : now.plusSeconds(timeoutRetryDelaySeconds);
                        if (orderTimeoutQueue.retry(claim, retryAt)) {
                            timeoutMetrics.retried();
                        }
                        continue;
                    }
                }
                // TransactionTemplate.execute has completed its commit before the token ACK.
                orderTimeoutQueue.ack(claim);
            } catch (BizException ignored) {
                acknowledgeHandledOrder(claim);
            } catch (Exception exception) {
                requeueExpiredOrder(claim);
                log.warn("Failed to process timeout claim for order {}; token retry or lease recovery will retry",
                        claim.orderId(), exception);
            }
        }
        return count;
    }

    private synchronized int expireDueOrdersFromDatabase(LocalDateTime now) {
        List<TradeOrder> page = orderRepository.findPendingExpiringBefore(
                now, fallbackCursorExpireAt, fallbackCursorId, Math.max(1, timeoutBatchSize));
        int count = 0;
        for (TradeOrder order : page) {
            try {
                if (Boolean.TRUE.equals(runInTransaction(() -> expireOrderIfPending(order.getId())))) {
                    count++;
                    timeoutMetrics.fallbackProcessed();
                    recordExpiration(order.getExpireAt());
                }
            } catch (Exception exception) {
                log.warn("Database timeout fallback failed for order {}; next scan will retry", order.getId(), exception);
            }
        }
        if (page.size() < Math.max(1, timeoutBatchSize)) {
            fallbackCursorExpireAt = null;
            fallbackCursorId = null;
        } else {
            TradeOrder last = page.getLast();
            fallbackCursorExpireAt = last.getExpireAt();
            fallbackCursorId = last.getId();
        }
        return count;
    }

    private void recordExpiration(LocalDateTime expireAt) {
        timeoutMetrics.expired();
        if (expireAt != null) {
            timeoutMetrics.closeDelay(Duration.between(expireAt, LocalDateTime.now(ZoneOffset.UTC)));
        }
    }

    private void acknowledgeHandledOrder(OrderTimeoutClaim claim) {
        try {
            orderTimeoutQueue.ack(claim);
        } catch (Exception exception) {
            timeoutMetrics.backendFailure();
            log.warn("Timeout ACK failed for order {}; lease recovery will retry", claim.orderId(), exception);
        }
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
            return false;
        }

        stateMachine.assertCanTransit(order.getStatus(), OrderStatus.EXPIRED);
        boolean updated = orderRepository.expireIfPendingAndDue(order.getId(), null);
        if (!updated) {
            return false;
        }

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

    private void requeueExpiredOrder(OrderTimeoutClaim claim) {
        try {
            if (orderTimeoutQueue.retry(claim, LocalDateTime.now(ZoneOffset.UTC).plusSeconds(timeoutRetryDelaySeconds))) {
                timeoutMetrics.retried();
            }
        } catch (Exception exception) {
            timeoutMetrics.backendFailure();
            log.warn("Timeout retry failed for order {}; durable scan and lease recovery will retry", claim.orderId(), exception);
        }
    }

    private <T> T runInTransaction(Supplier<T> supplier) {
        if (transactionTemplate == null) {
            synchronized (inMemoryTradeMonitor) {
                return supplier.get();
            }
        }
        return transactionTemplate.execute(status -> supplier.get());
    }

    private <T> T executeTrade(Supplier<T> supplier) {
        if (transactionTemplate != null) {
            return supplier.get();
        }
        // The development store has no transaction manager; serialize its multi-record operations.
        synchronized (inMemoryTradeMonitor) {
            return supplier.get();
        }
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
        return "CT" + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
    }
}
