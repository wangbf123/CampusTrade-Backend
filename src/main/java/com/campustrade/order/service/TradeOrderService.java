package com.campustrade.order.service;

import com.campustrade.common.exception.BizException;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class TradeOrderService {

    private final TradeOrderRepository orderRepository;
    private final ItemService itemService;
    private final NotificationOutboxService notificationOutboxService;
    private final OrderStateMachine stateMachine;
    private final OrderTimeoutQueue orderTimeoutQueue;
    private final IdempotencyService idempotencyService;
    private final long timeoutHours;
    private final int timeoutBatchSize;

    public TradeOrderService(
            TradeOrderRepository orderRepository,
            ItemService itemService,
            NotificationOutboxService notificationOutboxService,
            OrderStateMachine stateMachine,
            OrderTimeoutQueue orderTimeoutQueue,
            IdempotencyService idempotencyService,
            @Value("${app.order-timeout.hours:24}") long timeoutHours,
            @Value("${app.order-timeout.batch-size:50}") int timeoutBatchSize
    ) {
        this.orderRepository = orderRepository;
        this.itemService = itemService;
        this.notificationOutboxService = notificationOutboxService;
        this.stateMachine = stateMachine;
        this.orderTimeoutQueue = orderTimeoutQueue;
        this.idempotencyService = idempotencyService;
        this.timeoutHours = timeoutHours;
        this.timeoutBatchSize = timeoutBatchSize;
    }

    public OrderResponse createAppointment(
            AuthenticatedUser buyer,
            Long itemId,
            CreateAppointmentRequest request,
            String idempotencyKey
    ) {
        Item item = itemService.requireItem(itemId);
        if (item.getSellerId().equals(buyer.id())) {
            throw BizException.badRequest("不能预约自己的商品");
        }
        if (item.getStatus() != ItemStatus.ON_SALE) {
            throw BizException.conflict("商品当前不可预约");
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
        orderTimeoutQueue.enqueue(order.getId(), order.getExpireAt());

        notificationOutboxService.enqueue(
                item.getSellerId(),
                "APPOINTMENT_CREATED",
                "收到新的预约请求",
                "用户 " + buyer.username() + " 预约了你的商品：" + item.getTitle(),
                order.getId()
        );
        return OrderResponse.from(order);
    }

    public synchronized OrderResponse confirm(AuthenticatedUser seller, Long orderId) {
        TradeOrder order = requireOrder(orderId);
        requireSeller(seller.id(), order);
        stateMachine.assertCanTransit(order.getStatus(), OrderStatus.CONFIRMED);

        boolean reserved = itemService.reserveIfOnSale(order.getItemId());
        if (!reserved) {
            throw BizException.conflict("商品已被预约、售出或下架");
        }
        boolean updated = orderRepository.updateStatusIfCurrent(orderId, OrderStatus.PENDING, OrderStatus.CONFIRMED,
                target -> target.setConfirmedAt(LocalDateTime.now()));
        if (!updated) {
            itemService.restoreOnSaleIfReserved(order.getItemId());
            throw BizException.conflict("订单状态已变化，确认失败");
        }
        orderTimeoutQueue.remove(orderId);

        TradeOrder latest = requireOrder(orderId);
        notificationOutboxService.enqueue(
                latest.getBuyerId(),
                "APPOINTMENT_CONFIRMED",
                "卖家已确认预约",
                "卖家已确认你的预约，请按约定时间线下交易",
                latest.getId()
        );
        return OrderResponse.from(latest);
    }

    public synchronized OrderResponse reject(AuthenticatedUser seller, Long orderId) {
        TradeOrder order = requireOrder(orderId);
        requireSeller(seller.id(), order);
        stateMachine.assertCanTransit(order.getStatus(), OrderStatus.REJECTED);
        boolean updated = orderRepository.updateStatusIfCurrent(orderId, OrderStatus.PENDING, OrderStatus.REJECTED, null);
        if (!updated) {
            throw BizException.conflict("订单状态已变化，拒绝失败");
        }
        orderTimeoutQueue.remove(orderId);
        TradeOrder latest = requireOrder(orderId);
        notificationOutboxService.enqueue(latest.getBuyerId(), "APPOINTMENT_REJECTED", "卖家拒绝了预约", "可以继续看看其他商品", latest.getId());
        return OrderResponse.from(latest);
    }

    public synchronized OrderResponse cancel(AuthenticatedUser user, Long orderId, CancelOrderRequest request) {
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
            throw BizException.conflict("当前状态不能取消");
        }
        orderTimeoutQueue.remove(orderId);

        TradeOrder latest = requireOrder(orderId);
        Long receiverId = user.id().equals(latest.getBuyerId()) ? latest.getSellerId() : latest.getBuyerId();
        notificationOutboxService.enqueue(receiverId, "ORDER_CANCELLED", "预约已取消", "订单 " + latest.getOrderNo() + " 已取消", latest.getId());
        return OrderResponse.from(latest);
    }

    public synchronized OrderResponse complete(AuthenticatedUser user, Long orderId) {
        TradeOrder order = requireOrder(orderId);
        requireParticipant(user.id(), order);
        stateMachine.assertCanTransit(order.getStatus(), OrderStatus.COMPLETED);
        itemService.markSoldIfReserved(order.getItemId());
        boolean updated = orderRepository.updateStatusIfCurrent(orderId, OrderStatus.CONFIRMED, OrderStatus.COMPLETED,
                target -> target.setCompletedAt(LocalDateTime.now()));
        if (!updated) {
            throw BizException.conflict("订单状态已变化，完成失败");
        }
        orderTimeoutQueue.remove(orderId);
        TradeOrder latest = requireOrder(orderId);
        notificationOutboxService.enqueue(latest.getBuyerId(), "ORDER_COMPLETED", "交易已完成", "记得给对方评价，提升信用分", latest.getId());
        notificationOutboxService.enqueue(latest.getSellerId(), "ORDER_COMPLETED", "交易已完成", "记得给对方评价，提升信用分", latest.getId());
        return OrderResponse.from(latest);
    }

    public int expireDueOrders() {
        List<Long> dueOrderIds = orderTimeoutQueue.dueOrderIds(LocalDateTime.now(), timeoutBatchSize);
        int count = 0;
        for (Long orderId : dueOrderIds) {
            try {
                TradeOrder order = requireOrder(orderId);
                if (order.getStatus() != OrderStatus.PENDING) {
                    orderTimeoutQueue.remove(orderId);
                    continue;
                }
                stateMachine.assertCanTransit(order.getStatus(), OrderStatus.EXPIRED);
                boolean updated = orderRepository.updateStatusIfCurrent(order.getId(), OrderStatus.PENDING, OrderStatus.EXPIRED, null);
                if (updated) {
                    count++;
                    orderTimeoutQueue.remove(orderId);
                    notificationOutboxService.enqueue(order.getBuyerId(), "ORDER_EXPIRED", "预约已超时", "卖家未及时确认，预约已自动取消", order.getId());
                    notificationOutboxService.enqueue(order.getSellerId(), "ORDER_EXPIRED", "预约已超时", "你未及时处理预约，系统已自动取消", order.getId());
                } else {
                    orderTimeoutQueue.remove(orderId);
                }
            } catch (BizException ignored) {
                orderTimeoutQueue.remove(orderId);
                // 状态已经被其他操作推进时，本轮任务跳过即可。
            }
        }
        return count;
    }

    public List<OrderResponse> myBuyOrders(Long buyerId) {
        return orderRepository.findByBuyerId(buyerId).stream().map(OrderResponse::from).toList();
    }

    public List<OrderResponse> mySellOrders(Long sellerId) {
        return orderRepository.findBySellerId(sellerId).stream().map(OrderResponse::from).toList();
    }

    public TradeOrder requireOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> BizException.notFound("订单不存在"));
    }

    private void updateOrderStatus(Long orderId, OrderStatus expected, OrderStatus next, String cancelReason) {
        boolean updated = orderRepository.updateStatusIfCurrent(orderId, expected, next,
                target -> target.setCancelReason(cancelReason));
        if (!updated) {
            throw BizException.conflict("订单状态已变化，操作失败");
        }
    }

    private void requireSeller(Long userId, TradeOrder order) {
        if (!order.getSellerId().equals(userId)) {
            throw BizException.forbidden("只有卖家可以执行该操作");
        }
    }

    private void requireParticipant(Long userId, TradeOrder order) {
        if (!order.getSellerId().equals(userId) && !order.getBuyerId().equals(userId)) {
            throw BizException.forbidden("只能操作自己的订单");
        }
    }

    private String generateOrderNo() {
        return "CT" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + ThreadLocalRandom.current().nextInt(1000, 9999);
    }
}
