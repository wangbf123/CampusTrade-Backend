package com.campustrade.order.concurrency;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.campustrade.common.exception.BizException;
import com.campustrade.item.mapper.ItemImageMapper;
import com.campustrade.item.mapper.ItemMapper;
import com.campustrade.item.model.ConditionLevel;
import com.campustrade.item.model.Item;
import com.campustrade.item.model.ItemStatus;
import com.campustrade.item.repository.MysqlItemRepository;
import com.campustrade.order.mapper.TradeOrderMapper;
import com.campustrade.order.dto.OrderResponse;
import com.campustrade.order.model.OrderStatus;
import com.campustrade.order.model.TradeOrder;
import com.campustrade.order.repository.MysqlTradeOrderRepository;
import com.campustrade.risk.idempotency.MysqlAppointmentIdempotencyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in integration checks against a real MySQL 8 server, using the production
 * repositories and migrations. A randomly named database is created and dropped.
 * Set CAMPUS_MYSQL_IT=true and CAMPUS_MYSQL_IT_PASSWORD; optionally set
 * CAMPUS_MYSQL_IT_ADMIN_URL (default local port 3307) and CAMPUS_MYSQL_IT_USER.
 * The supplied user needs permission to create and drop the temporary database.
 */
@EnabledIfEnvironmentVariable(named = "CAMPUS_MYSQL_IT", matches = "true")
class MysqlOrderConcurrencyTest {

    private static final String DATABASE = "campus_concurrency_it_" + UUID.randomUUID().toString().replace("-", "");
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static MysqlItemRepository items;
    private static MysqlTradeOrderRepository orders;
    private static MysqlAppointmentIdempotencyRepository idempotency;
    private static TransactionTemplate transactions;
    private static String adminUrl;
    private static String username;
    private static String password;
    private static boolean databaseCreated;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        adminUrl = System.getenv().getOrDefault("CAMPUS_MYSQL_IT_ADMIN_URL",
                "jdbc:mysql://127.0.0.1:3307/?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false");
        username = System.getenv().getOrDefault("CAMPUS_MYSQL_IT_USER", "root");
        password = System.getenv("CAMPUS_MYSQL_IT_PASSWORD");
        assertNotNull(password, "Set CAMPUS_MYSQL_IT_PASSWORD securely before enabling MySQL integration tests");
        try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + DATABASE + "` CHARACTER SET utf8mb4");
            databaseCreated = true;
        }

        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(databaseUrl(adminUrl));
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMaximumPoolSize(16);
        pool.setMinimumIdle(2);
        pool.setConnectionTimeout(20000);
        pool.setConnectionInitSql("SET time_zone = '+00:00'");
        pool.setPoolName("campus-concurrency-it");
        dataSource = new HikariDataSource(pool);
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ItemMapper.class);
        configuration.addMapper(ItemImageMapper.class);
        configuration.addMapper(TradeOrderMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionTemplate sessions = new SqlSessionTemplate(factory.getObject());
        items = new MysqlItemRepository(sessions.getMapper(ItemMapper.class), sessions.getMapper(ItemImageMapper.class));
        orders = new MysqlTradeOrderRepository(sessions.getMapper(TradeOrderMapper.class));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        idempotency = new MysqlAppointmentIdempotencyRepository(jdbc,
                new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @AfterAll
    static void cleanUpDatabase() throws Exception {
        if (dataSource != null) {
            dataSource.close();
        }
        if (databaseCreated) {
            try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE `" + DATABASE + "`");
            }
        }
    }

    @Test
    void databaseSessionUsesUtcForDeadlineEvaluation() {
        assertEquals("+00:00", jdbc.queryForObject("SELECT @@session.time_zone", String.class));
        assertTrue(Duration.between(databaseNow(), LocalDateTime.now(ZoneOffset.UTC)).abs().toSeconds() < 3,
                "Database CURRENT_TIMESTAMP and application UTC clock must agree");
    }

    @Test
    void itemAndOrderMetadataUseUtcWithShanghaiJvmDefault() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            Item item = item();
            TradeOrder order = pending(item.getId(), 100L, databaseNow().plusHours(1));
            assertMetadataCloseToUtc(items.findById(item.getId()).orElseThrow(), orders.findById(order.getId()).orElseThrow());

            item.setDescription("updated while JVM uses Shanghai");
            transactions.executeWithoutResult(status -> items.save(item));
            order.setNote("updated while JVM uses Shanghai");
            orders.save(order);
            assertMetadataCloseToUtc(items.findById(item.getId()).orElseThrow(), orders.findById(order.getId()).orElseThrow());

            assertTrue(confirm(order));
            assertMetadataCloseToUtc(items.findById(item.getId()).orElseThrow(), orders.findById(order.getId()).orElseThrow());
            assertTrue(finish(order, false));
            items.increaseViewCount(item.getId());
            assertMetadataCloseToUtc(items.findById(item.getId()).orElseThrow(), orders.findById(order.getId()).orElseThrow());
        } finally {
            TimeZone.setDefault(original);
        }
    }

    private void assertMetadataCloseToUtc(Item item, TradeOrder order) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (LocalDateTime timestamp : List.of(item.getCreatedAt(), item.getUpdatedAt(),
                order.getCreatedAt(), order.getUpdatedAt())) {
            assertTrue(Duration.between(timestamp, now).abs().toSeconds() < 3,
                    "Persisted item/order metadata must be UTC, independently of JVM default timezone");
        }
    }

    @Test
    void twentyFourBuyersCompeteForOneItemWithOneConfirmedOrder() throws Exception {
        Item item = item();
        List<TradeOrder> contenders = new ArrayList<>();
        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (int buyer = 0; buyer < 24; buyer++) {
            TradeOrder order = pending(item.getId(), 100L + buyer, databaseNow().plusHours(1));
            contenders.add(order);
            attempts.add(() -> confirm(order));
        }

        List<Boolean> results = race(attempts);

        assertEquals(1, results.stream().filter(Boolean.TRUE::equals).count());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM trade_order WHERE item_id=? AND status='CONFIRMED'", Long.class, item.getId()));
        assertEquals(23, jdbc.queryForObject(
                "SELECT COUNT(*) FROM trade_order WHERE item_id=? AND status='PENDING'", Long.class, item.getId()));
        Long winningOrder = contenders.get(results.indexOf(true)).getId();
        Item stored = items.findById(item.getId()).orElseThrow();
        assertEquals(ItemStatus.RESERVED, stored.getStatus());
        assertEquals(winningOrder, stored.getReservedOrderId());
        assertEquals(1, stored.getVersion());
    }

    @Test
    void expiredOrderConfirmationRollsBackTheEarlierItemReservation() {
        Item item = item();
        TradeOrder expired = pending(item.getId(), 100L, databaseNow().minusHours(1));

        assertFalse(confirm(expired));

        Item stored = items.findById(item.getId()).orElseThrow();
        assertEquals(ItemStatus.ON_SALE, stored.getStatus());
        assertNull(stored.getReservedOrderId());
        assertEquals(0, stored.getVersion());
        assertEquals(OrderStatus.PENDING, orders.findById(expired.getId()).orElseThrow().getStatus());
    }

    @Test
    void concurrentCompletionAndCancellationLeaveMatchingItemAndOrderStates() throws Exception {
        for (int round = 0; round < 8; round++) {
            Item item = item();
            TradeOrder order = pending(item.getId(), 100L, databaseNow().plusHours(1));
            assertTrue(confirm(order));
            List<Boolean> results = race(List.of(
                    () -> finish(order, true),
                    () -> finish(order, false)
            ));

            assertEquals(1, results.stream().filter(Boolean.TRUE::equals).count());
            TradeOrder storedOrder = orders.findById(order.getId()).orElseThrow();
            Item storedItem = items.findById(item.getId()).orElseThrow();
            if (results.getFirst()) {
                assertEquals(OrderStatus.COMPLETED, storedOrder.getStatus());
                assertEquals(ItemStatus.SOLD, storedItem.getStatus());
                assertNotNull(storedOrder.getCompletedAt());
            } else {
                assertEquals(OrderStatus.CANCELLED, storedOrder.getStatus());
                assertEquals(ItemStatus.ON_SALE, storedItem.getStatus());
                assertEquals("concurrent cancellation", storedOrder.getCancelReason());
            }
            assertNull(storedItem.getReservedOrderId());
            assertEquals(2, storedItem.getVersion());
            assertEquals(2, storedOrder.getVersion());
        }
    }

    @Test
    void timeoutAndSellerConfirmationRespectTheDatabaseDeadline() throws Exception {
        Item item = item();
        TradeOrder due = pending(item.getId(), 100L, databaseNow().minusHours(1));

        List<Boolean> results = race(List.of(
                () -> confirm(due),
                () -> transactions.execute(status -> orders.expireIfPendingAndDue(due.getId(), null))
        ));

        assertEquals(List.of(false, true), results);
        assertEquals(OrderStatus.EXPIRED, orders.findById(due.getId()).orElseThrow().getStatus());
        Item stored = items.findById(item.getId()).orElseThrow();
        assertEquals(ItemStatus.ON_SALE, stored.getStatus());
        assertNull(stored.getReservedOrderId());
        assertEquals(0, stored.getVersion());
    }

    @Test
    void earlyTimeoutAndStaleReservationOwnerCannotChangeState() {
        Item item = item();
        TradeOrder future = pending(item.getId(), 100L, databaseNow().plusHours(1));
        assertFalse(orders.expireIfPendingAndDue(future.getId(), null));
        assertTrue(confirm(future));
        assertTrue(Boolean.TRUE.equals(transactions.execute(status -> {
            assertTrue(items.releaseReservation(item.getId(), future.getId()));
            return orders.updateStatusIfCurrent(future.getId(), OrderStatus.CONFIRMED, OrderStatus.CANCELLED, null);
        })));
        TradeOrder replacement = pending(item.getId(), 200L, databaseNow().plusHours(1));
        assertTrue(confirm(replacement));

        assertFalse(items.releaseReservation(item.getId(), future.getId()));
        assertFalse(items.sellReservation(item.getId(), future.getId()));

        Item stored = items.findById(item.getId()).orElseThrow();
        assertEquals(ItemStatus.RESERVED, stored.getStatus());
        assertEquals(replacement.getId(), stored.getReservedOrderId());
        assertEquals(OrderStatus.CONFIRMED, orders.findById(replacement.getId()).orElseThrow().getStatus());
    }

    @Test
    void duplicateTimeoutWorkersOnlyExpireTheOrderOnce() throws Exception {
        Item item = item();
        TradeOrder due = pending(item.getId(), 100L, databaseNow().minusHours(1));
        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            attempts.add(() -> transactions.execute(status -> orders.expireIfPendingAndDue(due.getId(), null)));
        }

        assertEquals(1, race(attempts).stream().filter(Boolean.TRUE::equals).count());
        TradeOrder stored = orders.findById(due.getId()).orElseThrow();
        assertEquals(OrderStatus.EXPIRED, stored.getStatus());
        assertEquals(1, stored.getVersion());
    }

    @Test
    void concurrentIdenticalRequestsCreateOneOrderAndOneOutboxEvent() throws Exception {
        Item item = item();
        String key = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        AtomicInteger executions = new AtomicInteger();
        List<Callable<OrderResponse>> attempts = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            attempts.add(() -> transactions.execute(status -> idempotency.execute(100L, key, "a".repeat(64), () -> {
                executions.incrementAndGet();
                TradeOrder order = pending(item.getId(), 100L, databaseNow().plusHours(1));
                insertOutbox(eventId, order.getId());
                return OrderResponse.from(order);
            })));
        }

        List<OrderResponse> responses = race(attempts);

        assertEquals(1, executions.get());
        assertTrue(responses.stream().allMatch(responses.getFirst()::equals));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE item_id=?", Long.class, item.getId()));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE event_id=?", Long.class, eventId));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM appointment_idempotency WHERE user_id=? AND request_key=?",
                Long.class, 100L, key));
    }

    @Test
    void replayReturnsTheOriginalSnapshotAndDifferentPayloadConflicts() {
        Item item = item();
        String key = UUID.randomUUID().toString();
        OrderResponse initial = transactions.execute(status -> idempotency.execute(100L, key, "b".repeat(64),
                () -> OrderResponse.from(pending(item.getId(), 100L, databaseNow().plusHours(1)))));
        TradeOrder stored = orders.findById(initial.id()).orElseThrow();
        assertTrue(confirm(stored));

        OrderResponse replay = transactions.execute(status -> idempotency.execute(100L, key, "b".repeat(64), () -> {
            fail("Replay must not execute the business operation");
            return null;
        }));
        BizException conflict = assertThrows(BizException.class,
                () -> transactions.execute(status -> idempotency.execute(100L, key, "c".repeat(64), () -> {
                    fail("A reused key with different payload must not execute the business operation");
                    return null;
                })));

        assertEquals(initial, replay);
        assertEquals(OrderStatus.PENDING, replay.status());
        assertEquals(OrderStatus.CONFIRMED, orders.findById(initial.id()).orElseThrow().getStatus());
        assertEquals(409, conflict.getStatus().value());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE item_id=?", Long.class, item.getId()));
    }

    @Test
    void failedBusinessTransactionRollsBackTheKeyOrderAndOutboxAndCanBeRetried() {
        Item item = item();
        String key = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();

        assertThrows(IllegalStateException.class, () -> transactions.execute(status ->
                idempotency.execute(100L, key, "d".repeat(64), () -> {
                    TradeOrder order = pending(item.getId(), 100L, databaseNow().plusHours(1));
                    insertOutbox(eventId, order.getId());
                    throw new IllegalStateException("injected business failure after writes");
                })));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE item_id=?", Long.class, item.getId()));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE event_id=?", Long.class, eventId));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM appointment_idempotency WHERE user_id=? AND request_key=?",
                Long.class, 100L, key));

        OrderResponse recovered = transactions.execute(status -> idempotency.execute(100L, key, "d".repeat(64), () -> {
            TradeOrder order = pending(item.getId(), 100L, databaseNow().plusHours(1));
            insertOutbox(eventId, order.getId());
            return OrderResponse.from(order);
        }));

        assertNotNull(recovered.id());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE item_id=?", Long.class, item.getId()));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE event_id=?", Long.class, eventId));
    }

    private void insertOutbox(String eventId, Long orderId) {
        jdbc.update("""
                INSERT INTO notification_outbox (event_id,receiver_id,type,title,content,related_id,status,created_at,updated_at)
                VALUES (?,1,'APPOINTMENT_CREATED','test notification','isolated integration event',?,'PENDING',NOW(),NOW())
                """, eventId, orderId);
    }

    private boolean confirm(TradeOrder order) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            if (!items.reserveIfOnSale(order.getItemId(), order.getId())) {
                return false;
            }
            if (!orders.confirmIfPendingAndNotExpired(order.getId(), target -> target.setConfirmedAt(databaseNow()))) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        }));
    }

    private boolean finish(TradeOrder order, boolean complete) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            boolean itemUpdated = complete
                    ? items.sellReservation(order.getItemId(), order.getId())
                    : items.releaseReservation(order.getItemId(), order.getId());
            if (!itemUpdated) {
                return false;
            }
            boolean orderUpdated = orders.updateStatusIfCurrent(order.getId(), OrderStatus.CONFIRMED,
                    complete ? OrderStatus.COMPLETED : OrderStatus.CANCELLED, target -> {
                        if (complete) {
                            target.setCompletedAt(databaseNow());
                        } else {
                            target.setCancelReason("concurrent cancellation");
                        }
                    });
            if (!orderUpdated) {
                status.setRollbackOnly();
            }
            return orderUpdated;
        }));
    }

    private Item item() {
        Item item = new Item();
        item.setSellerId(1L);
        item.setTitle("concurrency integration item");
        item.setDescription("isolated test data");
        item.setCategory("books");
        item.setPrice(new BigDecimal("12.00"));
        item.setConditionLevel(ConditionLevel.GOOD);
        item.setCampus("test campus");
        item.setTradePlace("library");
        item.setStatus(ItemStatus.ON_SALE);
        return transactions.execute(status -> items.save(item));
    }

    private TradeOrder pending(Long itemId, Long buyerId, LocalDateTime expireAt) {
        TradeOrder order = new TradeOrder();
        order.setOrderNo("IT" + UUID.randomUUID().toString().replace("-", "").substring(0, 28));
        order.setItemId(itemId);
        order.setBuyerId(buyerId);
        order.setSellerId(1L);
        order.setStatus(OrderStatus.PENDING);
        order.setExpectedTime(databaseNow().plusDays(1));
        order.setExpireAt(expireAt);
        return orders.save(order);
    }

    private LocalDateTime databaseNow() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
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
            assertTrue(ready.await(10, TimeUnit.SECONDS), "All contenders must be ready before the race starts");
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
        }
    }

    private static String databaseUrl(String configuredUrl) {
        int path = configuredUrl.indexOf('/', "jdbc:mysql://".length());
        int query = configuredUrl.indexOf('?', path);
        return configuredUrl.substring(0, path + 1) + DATABASE
                + (query < 0 ? "" : configuredUrl.substring(query));
    }
}
