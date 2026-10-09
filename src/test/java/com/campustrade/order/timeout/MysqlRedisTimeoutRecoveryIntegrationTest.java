package com.campustrade.order.timeout;

import com.campustrade.order.model.OrderStatus;
import com.campustrade.order.model.TradeOrder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MySQL + Redis + SIGKILL. Each suite owns and removes its random database and Redis namespace. */
@EnabledIfEnvironmentVariable(named = "CAMPUS_MYSQL_IT", matches = "true")
class MysqlRedisTimeoutRecoveryIntegrationTest {
    private static final String DATABASE = "campus_timeout_it_" + UUID.randomUUID().toString().replace("-", "");
    private static final String KEY = "{timeout-crash-it-" + UUID.randomUUID() + "}:pending";
    private static TimeoutIntegrationFixture fixture;
    private static boolean created;

    @BeforeAll
    static void createIsolatedDatabase() throws Exception {
        try (var connection = DriverManager.getConnection(TimeoutIntegrationFixture.adminUrl(),
                TimeoutIntegrationFixture.username(), TimeoutIntegrationFixture.password());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + DATABASE + "` CHARACTER SET utf8mb4");
            created = true;
        }
        fixture = new TimeoutIntegrationFixture(DATABASE, KEY);
        Flyway.configure().dataSource(fixture.dataSource).locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    static void removeIsolatedResources() throws Exception {
        if (fixture != null) {
            fixture.clearQueue();
            fixture.close();
        }
        if (created) {
            try (var connection = DriverManager.getConnection(TimeoutIntegrationFixture.adminUrl(),
                    TimeoutIntegrationFixture.username(), TimeoutIntegrationFixture.password());
                 var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE `" + DATABASE + "`");
            }
        }
    }

    @BeforeEach
    void clearOwnData() {
        fixture.jdbc.update("DELETE FROM notification_outbox");
        fixture.jdbc.update("DELETE FROM trade_order");
        fixture.clearQueue();
    }

    @Test
    void sigkillAfterClaimRecoversPendingOrderAndPublishesExactlyTwoEvents() throws Exception {
        TradeOrder order = dueOrder();
        fixture.queue().enqueue(order.getId(), order.getExpireAt());
        Process worker = worker("CLAIM");
        try {
            waitForMarker(worker, "CLAIMED");
            assertEquals(OrderStatus.PENDING, fixture.orders.findById(order.getId()).orElseThrow().getStatus());
            assertEquals(1, fixture.queue().snapshot(now()).processing());
        } finally {
            kill(worker);
        }

        awaitExpiredClaim();
        assertEquals(1, fixture.queue().recoverExpired(now(), 50));
        assertEquals(1, fixture.service(fixture.queue()).expireDueOrders());
        assertExpiredWithExactlyTwoEvents(order);
        assertEquals(0, fixture.queue().snapshot(now()).processing());
    }

    @Test
    void sigkillAfterDatabaseCommitBeforeAckDoesNotDuplicateOutboxEvents() throws Exception {
        TradeOrder order = dueOrder();
        fixture.queue().enqueue(order.getId(), order.getExpireAt());
        Process worker = worker("COMMIT");
        try {
            waitForMarker(worker, "DATABASE_COMMITTED");
            assertExpiredWithExactlyTwoEvents(order);
            assertEquals(1, fixture.queue().snapshot(now()).processing());
        } finally {
            kill(worker);
        }

        awaitExpiredClaim();
        assertEquals(1, fixture.queue().recoverExpired(now(), 50));
        assertEquals(0, fixture.service(fixture.queue()).expireDueOrders());
        assertExpiredWithExactlyTwoEvents(order);
        assertEquals(0, fixture.queue().snapshot(now()).processing());
    }

    @Test
    void completeRedisQueueLossIsRebuiltFromMysql() {
        TradeOrder order = dueOrder();
        fixture.queue().enqueue(order.getId(), order.getExpireAt());
        fixture.clearQueue(); // Delete only this suite's three keys; never FLUSHDB.
        OrderTimeoutRecoveryService recovery = new OrderTimeoutRecoveryService(
                fixture.orders, fixture.queue(), fixture.metrics, 50, 60);

        assertEquals(1, recovery.recoverAndReconcile(now()));
        assertEquals(1, fixture.service(fixture.queue()).expireDueOrders());
        assertExpiredWithExactlyTwoEvents(order);
    }

    @Test
    void failedAfterCommitEnqueueIsRecoveredByDatabaseScan() {
        TradeOrder order = dueOrder(); // The committed order deliberately has no queue entry.
        OrderTimeoutRecoveryService recovery = new OrderTimeoutRecoveryService(
                fixture.orders, fixture.queue(), fixture.metrics, 50, 60);

        assertEquals(1, recovery.recoverAndReconcile(now()));
        assertEquals(1, fixture.service(fixture.queue()).expireDueOrders());
        assertExpiredWithExactlyTwoEvents(order);
    }

    @Test
    void redisUnavailabilityFallsBackToBoundedDatabaseExpiration() {
        TradeOrder order = dueOrder();
        OrderTimeoutQueue unavailable = mock(OrderTimeoutQueue.class);
        when(unavailable.claimDue(any(), anyInt(), any())).thenThrow(new IllegalStateException("Injected Redis outage"));

        assertEquals(1, fixture.service(unavailable).expireDueOrders());
        assertExpiredWithExactlyTwoEvents(order);
        verify(unavailable, never()).ack(any());
    }

    @Test
    void prematureQueueTaskDoesNotExpireFutureOrderAndIsRescheduledToDatabaseDeadline() {
        TradeOrder order = orderAt(now().plusHours(1));
        fixture.queue().enqueue(order.getId(), now().minusMinutes(1));

        assertEquals(0, fixture.service(fixture.queue()).expireDueOrders());
        assertEquals(OrderStatus.PENDING, fixture.orders.findById(order.getId()).orElseThrow().getStatus());
        assertTrue(fixture.queue().claimDue(now(), 50, Duration.ofSeconds(2)).isEmpty());
        assertEquals(1, fixture.queue().snapshot(now()).pending());
        assertEquals(0L, eventCount(order));
    }

    private TradeOrder dueOrder() { return orderAt(now().minusMinutes(5)); }

    private TradeOrder orderAt(LocalDateTime deadline) {
        TradeOrder order = new TradeOrder();
        order.setOrderNo("IT" + UUID.randomUUID().toString().replace("-", "").substring(0, 30));
        order.setItemId(1L);
        order.setBuyerId(101L);
        order.setSellerId(202L);
        order.setExpectedTime(now().plusDays(1));
        order.setStatus(OrderStatus.PENDING);
        order.setExpireAt(deadline);
        return fixture.orders.save(order);
    }

    private void assertExpiredWithExactlyTwoEvents(TradeOrder order) {
        assertEquals(OrderStatus.EXPIRED, fixture.orders.findById(order.getId()).orElseThrow().getStatus());
        assertEquals(2L, eventCount(order));
    }

    private Long eventCount(TradeOrder order) {
        return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE related_id=? AND type='ORDER_EXPIRED'",
                Long.class, order.getId());
    }

    private void awaitExpiredClaim() {
        await().atMost(Duration.ofSeconds(8)).until(() -> fixture.queue().snapshot(now()).expiredLeases() == 1);
    }

    private Process worker(String phase) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new ProcessBuilder(java, "-cp", classpath, TimeoutCrashWorker.class.getName(), DATABASE, KEY, phase)
                .redirectErrorStream(true).start();
    }

    private void waitForMarker(Process process, String marker) throws Exception {
        CompletableFuture<Boolean> reached = CompletableFuture.supplyAsync(() -> {
            try (BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = output.readLine()) != null) {
                    if (line.equals(marker)) {
                        return true;
                    }
                }
                return false;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        assertTrue(reached.get(30, TimeUnit.SECONDS), "Worker exited before protocol marker " + marker);
    }

    private void kill(Process process) throws Exception {
        process.destroyForcibly();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "SIGKILL worker did not exit");
    }

    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}
