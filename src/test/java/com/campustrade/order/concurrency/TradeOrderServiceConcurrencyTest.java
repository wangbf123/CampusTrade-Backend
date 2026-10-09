package com.campustrade.order.concurrency;

import com.campustrade.cache.HotItemRankService;
import com.campustrade.cache.ItemDetailCache;
import com.campustrade.common.exception.BizException;
import com.campustrade.common.web.AuthenticatedUser;
import com.campustrade.item.model.Item;
import com.campustrade.item.model.ItemStatus;
import com.campustrade.item.repository.InMemoryItemRepository;
import com.campustrade.item.service.ItemService;
import com.campustrade.notification.model.OutboxStatus;
import com.campustrade.notification.repository.InMemoryNotificationOutboxRepository;
import com.campustrade.notification.service.NotificationOutboxService;
import com.campustrade.observability.OrderTimeoutMetrics;
import com.campustrade.order.dto.CancelOrderRequest;
import com.campustrade.order.dto.CreateAppointmentRequest;
import com.campustrade.order.dto.OrderResponse;
import com.campustrade.order.model.OrderStatus;
import com.campustrade.order.model.TradeOrder;
import com.campustrade.order.repository.InMemoryTradeOrderRepository;
import com.campustrade.order.service.OrderStateMachine;
import com.campustrade.order.service.TradeOrderService;
import com.campustrade.order.timeout.InMemoryOrderTimeoutQueue;
import com.campustrade.risk.idempotency.IdempotencyService;
import com.campustrade.risk.idempotency.InMemoryAppointmentIdempotencyRepository;
import com.campustrade.risk.idempotency.InMemoryIdempotencyStore;
import com.campustrade.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class TradeOrderServiceConcurrencyTest {

    private static final AuthenticatedUser SELLER = new AuthenticatedUser(1L, "seller", UserRole.USER);
    private static final AuthenticatedUser BUYER = new AuthenticatedUser(2L, "buyer", UserRole.USER);

    @Test
    void appointmentDeadlineAndConfirmationUseUtcWhenJvmDefaultIsShanghai() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            Fixture fixture = new Fixture();
            Item item = fixture.item();
            LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
            CreateAppointmentRequest utcRequest = new CreateAppointmentRequest(before.plusHours(1), "UTC appointment");

            OrderResponse created = fixture.service.createAppointment(BUYER, item.getId(), utcRequest, "timezone-key");
            LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC);

            assertEquals(utcRequest.expectedTime(), created.expectedTime());
            assertFalse(created.expireAt().isBefore(before.plusHours(24)));
            assertFalse(created.expireAt().isAfter(after.plusHours(24)));
            OrderResponse confirmed = fixture.service.confirm(SELLER, created.id());
            assertEquals(OrderStatus.CONFIRMED, confirmed.status());
            assertFalse(confirmed.confirmedAt().isBefore(before));
            assertFalse(confirmed.confirmedAt().isAfter(LocalDateTime.now(ZoneOffset.UTC)));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void inMemoryItemAndOrderMetadataStayUtcWithShanghaiJvmDefault() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            Fixture fixture = new Fixture();
            Item item = fixture.item();
            OrderResponse created = fixture.service.createAppointment(BUYER, item.getId(), request("UTC metadata"), "metadata-key");
            TradeOrder order = fixture.service.requireOrder(created.id());
            assertMetadataCloseToUtc(item, order);

            item.setDescription("updated metadata");
            fixture.items.save(item);
            order.setNote("updated metadata");
            fixture.orders.save(order);
            assertMetadataCloseToUtc(item, order);

            fixture.service.confirm(SELLER, created.id());
            assertMetadataCloseToUtc(item, order);
            fixture.service.cancel(BUYER, created.id(), new CancelOrderRequest("UTC cancellation"));
            fixture.items.increaseViewCount(item.getId());
            assertMetadataCloseToUtc(item, order);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    private void assertMetadataCloseToUtc(Item item, TradeOrder order) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (LocalDateTime timestamp : List.of(item.getCreatedAt(), item.getUpdatedAt(),
                order.getCreatedAt(), order.getUpdatedAt())) {
            assertTrue(Duration.between(timestamp, now).abs().toSeconds() < 3,
                    "Item/order metadata must be UTC, independently of JVM default timezone");
        }
    }

    @Test
    void twentyFourIdenticalRequestsCreateOneOrderAndOneNotification() throws Exception {
        Fixture fixture = new Fixture();
        Item item = fixture.item();
        CreateAppointmentRequest request = request("meet at library");
        List<Callable<OrderResponse>> attempts = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            attempts.add(() -> fixture.service.createAppointment(BUYER, item.getId(), request, "same-request"));
        }

        List<OrderResponse> responses = race(attempts);

        assertTrue(responses.stream().allMatch(responses.getFirst()::equals));
        assertEquals(1, fixture.orders.findByBuyerId(BUYER.id()).size());
        assertEquals(1, fixture.outbox.countByStatus(OutboxStatus.PENDING));
        assertEquals(1, fixture.queue.snapshot(LocalDateTime.now(ZoneOffset.UTC)).pending());
        assertEquals(ItemStatus.ON_SALE, item.getStatus());
    }

    @Test
    void keyedReplaySurvivesConfirmationAndKeepsTheOriginalResponseSnapshot() {
        Fixture fixture = new Fixture();
        Item item = fixture.item();
        CreateAppointmentRequest request = request("original note");
        OrderResponse initial = fixture.service.createAppointment(BUYER, item.getId(), request, " snapshot-key ");
        fixture.service.confirm(SELLER, initial.id());

        OrderResponse replay = fixture.service.createAppointment(BUYER, item.getId(), request, "snapshot-key");

        assertEquals(initial, replay);
        assertEquals(OrderStatus.PENDING, replay.status());
        assertEquals(OrderStatus.CONFIRMED, fixture.service.requireOrder(initial.id()).getStatus());
        assertEquals(ItemStatus.RESERVED, item.getStatus());
        assertEquals(initial.id(), item.getReservedOrderId());
        assertEquals(1, fixture.orders.findByBuyerId(BUYER.id()).size());
        assertEquals(2, fixture.outbox.countByStatus(OutboxStatus.PENDING));
    }

    @Test
    void reusingAKeyForAnotherItemNoteOrTimeConflictsWithoutNewWrites() {
        Fixture fixture = new Fixture();
        Item first = fixture.item();
        Item second = fixture.item();
        CreateAppointmentRequest request = request("original note");
        fixture.service.createAppointment(BUYER, first.getId(), request, "one-key");

        assertConflict(() -> fixture.service.createAppointment(BUYER, second.getId(), request, "one-key"));
        assertConflict(() -> fixture.service.createAppointment(BUYER, first.getId(),
                new CreateAppointmentRequest(request.expectedTime(), "changed note"), "one-key"));
        assertConflict(() -> fixture.service.createAppointment(BUYER, first.getId(),
                new CreateAppointmentRequest(request.expectedTime().plusMinutes(1), request.note()), "one-key"));

        assertEquals(1, fixture.orders.findByBuyerId(BUYER.id()).size());
        assertEquals(1, fixture.outbox.countByStatus(OutboxStatus.PENDING));
    }

    @Test
    void unkeyedRequestsRetainTheDuplicateSubmissionGuard() {
        Fixture fixture = new Fixture();
        Item item = fixture.item();
        CreateAppointmentRequest request = request("unkeyed request");
        fixture.service.createAppointment(BUYER, item.getId(), request, null);

        assertConflict(() -> fixture.service.createAppointment(BUYER, item.getId(), request, " "));

        assertEquals(1, fixture.orders.findByBuyerId(BUYER.id()).size());
        assertEquals(1, fixture.outbox.countByStatus(OutboxStatus.PENDING));
    }

    @Test
    void confirmingAnExpiredOrderDoesNotReserveTheItemOrEmitAnEvent() {
        Fixture fixture = new Fixture();
        Item item = fixture.item();
        OrderResponse order = fixture.service.createAppointment(BUYER, item.getId(), request("expires"), "expire-key");
        fixture.service.requireOrder(order.id()).setExpireAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
        long itemVersion = item.getVersion();

        assertConflict(() -> fixture.service.confirm(SELLER, order.id()));

        assertEquals(ItemStatus.ON_SALE, item.getStatus());
        assertNull(item.getReservedOrderId());
        assertEquals(itemVersion, item.getVersion());
        assertEquals(OrderStatus.PENDING, fixture.service.requireOrder(order.id()).getStatus());
        assertEquals(1, fixture.outbox.countByStatus(OutboxStatus.PENDING));
    }

    @Test
    void confirmationAndCancellationRaceEndsWithAnAvailableItemAndCancelledOrder() throws Exception {
        for (int round = 0; round < 8; round++) {
            Fixture fixture = new Fixture();
            Item item = fixture.item();
            OrderResponse order = fixture.service.createAppointment(BUYER, item.getId(), request("race"), "race-key");

            List<Boolean> results = race(List.of(
                    () -> succeeds(() -> fixture.service.confirm(SELLER, order.id())),
                    () -> succeeds(() -> fixture.service.cancel(BUYER, order.id(), new CancelOrderRequest("cancelled")))
            ));

            assertTrue(results.get(1));
            assertEquals(OrderStatus.CANCELLED, fixture.service.requireOrder(order.id()).getStatus());
            assertEquals(ItemStatus.ON_SALE, item.getStatus());
            assertNull(item.getReservedOrderId());
            assertEquals(results.getFirst() ? 3 : 2, fixture.outbox.countByStatus(OutboxStatus.PENDING));
        }
    }

    @Test
    void completionAndCancellationRaceHasOneSuccessfulFinalTransition() throws Exception {
        for (int round = 0; round < 8; round++) {
            Fixture fixture = new Fixture();
            Item item = fixture.item();
            OrderResponse order = fixture.service.createAppointment(BUYER, item.getId(), request("race"), "race-key");
            fixture.service.confirm(SELLER, order.id());

            List<Boolean> results = race(List.of(
                    () -> succeeds(() -> fixture.service.complete(BUYER, order.id())),
                    () -> succeeds(() -> fixture.service.cancel(BUYER, order.id(), new CancelOrderRequest("cancelled")))
            ));

            assertEquals(1, results.stream().filter(Boolean.TRUE::equals).count());
            if (results.getFirst()) {
                assertEquals(OrderStatus.COMPLETED, fixture.service.requireOrder(order.id()).getStatus());
                assertEquals(ItemStatus.SOLD, item.getStatus());
                assertEquals(4, fixture.outbox.countByStatus(OutboxStatus.PENDING));
            } else {
                assertEquals(OrderStatus.CANCELLED, fixture.service.requireOrder(order.id()).getStatus());
                assertEquals(ItemStatus.ON_SALE, item.getStatus());
                assertEquals(3, fixture.outbox.countByStatus(OutboxStatus.PENDING));
            }
            assertNull(item.getReservedOrderId());
        }
    }

    private CreateAppointmentRequest request(String note) {
        return new CreateAppointmentRequest(LocalDateTime.now(ZoneOffset.UTC).plusDays(1), note);
    }

    private void assertConflict(Runnable action) {
        assertEquals(409, assertThrows(BizException.class, action::run).getStatus().value());
    }

    private boolean succeeds(Runnable action) {
        try {
            action.run();
            return true;
        } catch (BizException conflict) {
            assertEquals(409, conflict.getStatus().value());
            return false;
        }
    }

    private static <T> List<T> race(List<Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(tasks.size())) {
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return task.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
        }
    }

    private static class Fixture {
        private final InMemoryItemRepository items = new InMemoryItemRepository();
        private final InMemoryTradeOrderRepository orders = new InMemoryTradeOrderRepository();
        private final InMemoryNotificationOutboxRepository outbox = new InMemoryNotificationOutboxRepository();
        private final InMemoryOrderTimeoutQueue queue = new InMemoryOrderTimeoutQueue();
        private final TradeOrderService service;

        @SuppressWarnings("unchecked")
        Fixture() {
            service = new TradeOrderService(orders,
                    new ItemService(items, mock(ItemDetailCache.class), mock(HotItemRankService.class)),
                    new NotificationOutboxService(outbox), new OrderStateMachine(), queue,
                    new IdempotencyService(new InMemoryIdempotencyStore(), 300),
                    new InMemoryAppointmentIdempotencyRepository(),
                    mock(ObjectProvider.class), 24, 50, 60, 120, new OrderTimeoutMetrics());
        }

        Item item() {
            Item item = new Item();
            item.setSellerId(SELLER.id());
            item.setTitle("test item");
            item.setStatus(ItemStatus.ON_SALE);
            return items.save(item);
        }
    }
}
