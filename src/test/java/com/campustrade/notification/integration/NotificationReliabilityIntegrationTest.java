package com.campustrade.notification.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.campustrade.admin.mapper.AdminOperationLogMapper;
import com.campustrade.admin.repository.MysqlAdminOperationLogRepository;
import com.campustrade.admin.service.AdminAuditService;
import com.campustrade.common.exception.BizException;
import com.campustrade.common.web.AuthenticatedUser;
import com.campustrade.message.mapper.MessageMapper;
import com.campustrade.message.repository.MysqlMessageRepository;
import com.campustrade.message.service.MessageService;
import com.campustrade.notification.admin.*;
import com.campustrade.notification.mapper.NotificationOutboxMapper;
import com.campustrade.notification.model.*;
import com.campustrade.notification.rabbitmq.*;
import com.campustrade.notification.repository.*;
import com.campustrade.notification.service.*;
import com.campustrade.user.model.UserRole;
import com.rabbitmq.client.GetResponse;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt in with CAMPUS_C3_IT=true. Uses an explicitly isolated test database and uniquely named broker queues. */
@EnabledIfEnvironmentVariable(named = "CAMPUS_C3_IT", matches = "true")
class NotificationReliabilityIntegrationTest {
    private static HikariDataSource dataSource;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static NotificationOutboxRepository repository;
    private static NotificationOutboxService enqueue;
    private static NotificationMessageConsumer consumer;
    private static NotificationRecoveryService recovery;
    private static AdminAuditService audit;
    private static CachingConnectionFactory connection;
    private static RabbitTemplate template;
    private static RabbitAdmin rabbitAdmin;
    private static RabbitNotificationProperties properties;
    private static RabbitNotificationPublisher publisher;
    private static OutboxPublishService dispatcher;
    private static RabbitDeadLetterRecoveryService deadLetters;
    private static SimpleMeterRegistry metrics;
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(99L, "integration-admin", UserRole.ADMIN);

    @Configuration
    @EnableTransactionManagement
    static class Transactions { }

    @BeforeAll
    static void infrastructure() throws Exception {
        String url = System.getenv().getOrDefault("CAMPUS_C3_JDBC_URL",
                "jdbc:mysql://127.0.0.1:3307/campus_c3_test?serverTimezone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false");
        assertTrue(url.matches(".*[/]campus_c3_[a-zA-Z0-9_]+[?].*"), "An isolated campus_c3_* database is required");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(System.getenv().getOrDefault("CAMPUS_C3_DB_USER", "campus"));
        config.setPassword(System.getenv().getOrDefault("CAMPUS_C3_DB_PASSWORD", "campus123"));
        config.setMaximumPoolSize(12);
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        MybatisConfiguration mybatis = new MybatisConfiguration();
        mybatis.setMapUnderscoreToCamelCase(true);
        mybatis.addMapper(NotificationOutboxMapper.class);
        mybatis.addMapper(MessageMapper.class);
        mybatis.addMapper(AdminOperationLogMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(mybatis);
        SqlSessionTemplate sessions = new SqlSessionTemplate(factory.getObject());
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("mysql", "rabbitmq");
        context.register(Transactions.class);
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean("outboxRepository", MysqlNotificationOutboxRepository.class,
                () -> new MysqlNotificationOutboxRepository(sessions.getMapper(NotificationOutboxMapper.class)));
        context.registerBean("messageService", MessageService.class,
                () -> new MessageService(new MysqlMessageRepository(sessions.getMapper(MessageMapper.class))));
        context.registerBean("consumer", NotificationMessageConsumer.class,
                () -> new NotificationMessageConsumer(context.getBean(MessageService.class)));
        context.registerBean("audit", AdminAuditService.class,
                () -> spy(new AdminAuditService(new MysqlAdminOperationLogRepository(sessions.getMapper(AdminOperationLogMapper.class)))));
        context.registerBean("recovery", NotificationRecoveryService.class,
                () -> new NotificationRecoveryService(context.getBean(NotificationOutboxRepository.class), context.getBean(AdminAuditService.class)));
        context.refresh();
        repository = context.getBean(NotificationOutboxRepository.class);
        consumer = context.getBean(NotificationMessageConsumer.class);
        recovery = context.getBean(NotificationRecoveryService.class);
        audit = context.getBean(AdminAuditService.class);
        enqueue = new NotificationOutboxService(repository);
        connection = new CachingConnectionFactory(System.getenv().getOrDefault("CAMPUS_C3_RABBIT_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("CAMPUS_C3_RABBIT_PORT", "5673")));
        connection.setUsername(System.getenv().getOrDefault("CAMPUS_C3_RABBIT_USER", "guest"));
        connection.setPassword(System.getenv().getOrDefault("CAMPUS_C3_RABBIT_PASSWORD", "guest"));
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connection.setPublisherReturns(true);
        properties = new RabbitNotificationProperties();
        String prefix = "campus.c3.it." + UUID.randomUUID();
        properties.setNotificationExchange(prefix + ".exchange");
        properties.setNotificationQueue(prefix + ".queue");
        properties.setNotificationDeadLetterExchange(prefix + ".dlx");
        properties.setNotificationDeadLetterQueue(prefix + ".dlq");
        properties.setNotificationRetryExchange(prefix + ".retry.exchange");
        properties.setNotificationRetryQueue(prefix + ".retry.queue");
        properties.setConsumerRetryDelayMs(100);
        RabbitNotificationConfig declarations = new RabbitNotificationConfig();
        rabbitAdmin = new RabbitAdmin(connection);
        var exchange = declarations.notificationExchange(properties);
        var queue = declarations.notificationQueue(properties);
        var dlx = declarations.notificationDeadLetterExchange(properties);
        var dlq = declarations.notificationDeadLetterQueue(properties);
        var retryExchange = declarations.notificationRetryExchange(properties);
        var retryQueue = declarations.notificationRetryQueue(properties);
        for (var value : List.of(exchange, dlx, retryExchange)) { rabbitAdmin.declareExchange(value); }
        for (var value : List.of(queue, dlq, retryQueue)) { rabbitAdmin.declareQueue(value); }
        rabbitAdmin.declareBinding(declarations.notificationBinding(exchange, queue, properties));
        rabbitAdmin.declareBinding(declarations.notificationDeadLetterBinding(dlx, dlq, properties));
        rabbitAdmin.declareBinding(declarations.notificationRetryBinding(retryExchange, retryQueue, properties));
        template = new RabbitTemplate(connection);
        template.setMessageConverter(declarations.rabbitMessageConverter());
        declarations.rabbitTemplateCustomizer().customize(template);
        publisher = new RabbitNotificationPublisher(template, properties);
        metrics = new SimpleMeterRegistry();
        dispatcher = new OutboxPublishService(repository, publisher, 20, 3, 30, metrics);
        deadLetters = new RabbitDeadLetterRecoveryService(template, properties, recovery);
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM notification_outbox");
        jdbc.update("DELETE FROM message");
        jdbc.update("DELETE FROM admin_operation_log");
        org.mockito.Mockito.reset(audit);
        rabbitAdmin.purgeQueue(properties.getNotificationQueue(), false);
        rabbitAdmin.purgeQueue(properties.getNotificationDeadLetterQueue(), false);
        rabbitAdmin.purgeQueue(properties.getNotificationRetryQueue(), false);
    }

    @AfterAll
    static void close() {
        if (rabbitAdmin != null) {
            for (String queue : List.of(properties.getNotificationQueue(), properties.getNotificationDeadLetterQueue(), properties.getNotificationRetryQueue())) { rabbitAdmin.deleteQueue(queue); }
            for (String exchange : List.of(properties.getNotificationExchange(), properties.getNotificationDeadLetterExchange(), properties.getNotificationRetryExchange())) { rabbitAdmin.deleteExchange(exchange); }
        }
        if (connection != null) { connection.destroy(); }
        if (context != null) { context.close(); }
        if (dataSource != null) { dataSource.close(); }
    }

    @Test
    void twoInstancesClaimSixtyEventsWithoutOverlapAndExpiredOwnerIsFenced() throws Exception {
        for (int i = 0; i < 60; i++) { event(); }
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> repository.claimDue(LocalDateTime.now(), 40, Duration.ofSeconds(30)));
            var b = pool.submit(() -> repository.claimDue(LocalDateTime.now(), 40, Duration.ofSeconds(30)));
            var first = a.get(10, TimeUnit.SECONDS);
            var second = b.get(10, TimeUnit.SECONDS);
            var subsequent = repository.claimDue(LocalDateTime.now(), 60, Duration.ofSeconds(30));
            assertEquals(60, first.size() + second.size() + subsequent.size());
            assertTrue(first.stream().noneMatch(value -> second.stream().anyMatch(other -> other.getEventId().equals(value.getEventId()))));
            assertTrue(subsequent.stream().noneMatch(value -> first.stream().anyMatch(other -> other.getEventId().equals(value.getEventId()))
                    || second.stream().anyMatch(other -> other.getEventId().equals(value.getEventId()))));
            var crashed = first.isEmpty() ? second.getFirst() : first.getFirst();
            jdbc.update("UPDATE notification_outbox SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE event_id=?", crashed.getEventId());
            var reclaimed = repository.claimDue(LocalDateTime.now(), 1, Duration.ofSeconds(30)).getFirst();
            assertEquals(crashed.getEventId(), reclaimed.getEventId());
            assertFalse(repository.markPublished(crashed.getEventId(), crashed.getClaimToken()));
            assertFalse(repository.markFailed(crashed.getEventId(), crashed.getClaimToken(), "old worker", 1, 3));
            assertTrue(repository.markPublished(reclaimed.getEventId(), reclaimed.getClaimToken()));
        }
    }

    @Test
    void publishThenLoseDatabaseAckProducesDuplicateTransportAndSingleMessage() {
        var event = event();
        var claim = repository.claimDue(LocalDateTime.now(), 1, Duration.ofSeconds(30)).getFirst();
        publisher.publish(claim); // Simulate process exit after Confirm, before marking PUBLISHED.
        jdbc.update("UPDATE notification_outbox SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE event_id=?", event.getEventId());
        assertEquals(1, dispatcher.publishPending());
        assertEquals(2, queueDepth(properties.getNotificationQueue()));
        consumeOne();
        consumeOne();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM message WHERE event_id=?", Integer.class, event.getEventId()));
    }

    @Test
    void concurrentDuplicateConsumersAndCommitBeforeLostAckRemainIdempotent() throws Exception {
        var event = event();
        publisher.publish(event);
        template.execute(channel -> {
            GetResponse received = channel.basicGet(properties.getNotificationQueue(), false);
            assertNotNull(received);
            consumer.consume(decode(received)); // Commit, then close the physical channel without ACK.
            ((org.springframework.amqp.rabbit.connection.ChannelProxy) channel).getTargetChannel().close();
            return null;
        });
        await(() -> queueDepth(properties.getNotificationQueue()) == 1);
        consumeOne();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var calls = java.util.stream.IntStream.range(0, 16).<java.util.concurrent.Callable<Void>>mapToObj(index -> () -> {
                consumer.consume(NotificationEventPayload.from(event)); return null;
            }).toList();
            for (var future : pool.invokeAll(calls)) { future.get(); }
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM message WHERE event_id=?", Integer.class, event.getEventId()));
    }

    @Test
    void returnedMessageRetriesAndFailsAtExactConfiguredLimitThenAuditedReplaySucceeds() {
        var event = event();
        String routing = properties.getNotificationRoutingKey();
        properties.setNotificationRoutingKey("no.binding.exists");
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertEquals(0, dispatcher.publishPending());
            var latest = repository.findByEventId(event.getEventId()).orElseThrow();
            assertEquals(attempt, latest.getRetryCount());
            assertEquals(attempt == 3 ? OutboxStatus.FAILED : OutboxStatus.PENDING, latest.getStatus());
            jdbc.update("UPDATE notification_outbox SET next_retry_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE event_id=?", event.getEventId());
        }
        properties.setNotificationRoutingKey(routing);
        assertEquals(0, dispatcher.publishPending());
        recovery.replayFailed(ADMIN, event.getEventId(), "routing fixed");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM admin_operation_log WHERE operation_type='REPLAY_NOTIFICATION'", Integer.class));
        assertEquals(1, dispatcher.publishPending());
        consumeOne();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM message WHERE event_id=?", Integer.class, event.getEventId()));
    }

    @Test
    void realBrokerDeadLetterIsSelectedPersistedAuditedAndReplayedWithSameEventId() {
        var event = event();
        assertEquals(1, dispatcher.publishPending());
        template.execute(channel -> {
            var received = channel.basicGet(properties.getNotificationQueue(), false);
            assertNotNull(received);
            channel.basicNack(received.getEnvelope().getDeliveryTag(), false, false);
            return null;
        });
        await(() -> queueDepth(properties.getNotificationDeadLetterQueue()) == 1);
        assertEquals(event.getEventId(), deadLetters.peek(ADMIN).orElseThrow().event().eventId());
        await(() -> queueDepth(properties.getNotificationDeadLetterQueue()) == 1);
        assertThrows(BizException.class, () -> deadLetters.replay(ADMIN, new DeadLetterReplayRequest("another-event", "select correctly")));
        await(() -> queueDepth(properties.getNotificationDeadLetterQueue()) == 1);
        var replayed = deadLetters.replay(ADMIN, new DeadLetterReplayRequest(event.getEventId(), "dependency recovered"));
        assertEquals(event.getEventId(), replayed.getEventId());
        assertEquals(0, queueDepth(properties.getNotificationDeadLetterQueue()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM admin_operation_log WHERE operation_type='REPLAY_NOTIFICATION_DLQ'", Integer.class));
        assertEquals(1, dispatcher.publishPending());
        consumeOne();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM message WHERE event_id=?", Integer.class, event.getEventId()));
    }

    @Test
    void failedAuditRollsBackReplayAndLeavesDeadLetterAvailable() {
        var event = event();
        assertEquals(1, dispatcher.publishPending());
        template.execute(channel -> {
            var received = channel.basicGet(properties.getNotificationQueue(), false);
            channel.basicNack(received.getEnvelope().getDeliveryTag(), false, false); return null;
        });
        await(() -> queueDepth(properties.getNotificationDeadLetterQueue()) == 1);
        doThrow(new IllegalStateException("audit unavailable")).when(audit).record(anyLong(), anyString(), anyString(), anyLong(), anyString());
        assertThrows(RuntimeException.class, () -> deadLetters.replay(ADMIN, new DeadLetterReplayRequest(event.getEventId(), "dependency recovered")));
        assertEquals(OutboxStatus.PUBLISHED, repository.findByEventId(event.getEventId()).orElseThrow().getStatus());
        assertEquals(0, repository.findByEventId(event.getEventId()).orElseThrow().getReplayCount());
        await(() -> queueDepth(properties.getNotificationDeadLetterQueue()) == 1);
    }

    @Test
    void delayedRetryQueueReturnsTheMessageAndConsumerEventuallyCommits() {
        var event = event();
        publisher.publish(event);
        NotificationMessageConsumer temporarilyFailing = mock(NotificationMessageConsumer.class);
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("database unavailable"))
                .when(temporarilyFailing).consume(any());
        var listener = new RabbitNotificationListener(temporarilyFailing, publisher, properties, metrics);
        template.execute(channel -> {
            var received = channel.basicGet(properties.getNotificationQueue(), false);
            listener.onMessage(decode(received), message(received), channel); return null;
        });
        await(() -> queueDepth(properties.getNotificationQueue()) == 1);
        consumeOne();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM message WHERE event_id=?", Integer.class, event.getEventId()));
        assertEquals(0, queueDepth(properties.getNotificationDeadLetterQueue()));
    }

    @Test
    void retryExhaustionMovesOriginalIdentityToRealDeadLetterQueue() {
        var event = event();
        publisher.publish(event);
        NotificationMessageConsumer unavailable = mock(NotificationMessageConsumer.class);
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("database unavailable"))
                .when(unavailable).consume(any());
        var listener = new RabbitNotificationListener(unavailable, publisher, properties, metrics);
        for (int attempt = 0; attempt <= properties.getConsumerMaxRetry(); attempt++) {
            await(() -> queueDepth(properties.getNotificationQueue()) == 1);
            template.execute(channel -> {
                var received = channel.basicGet(properties.getNotificationQueue(), false);
                listener.onMessage(decode(received), message(received), channel); return null;
            });
        }
        await(() -> queueDepth(properties.getNotificationDeadLetterQueue()) == 1);
        assertEquals(event.getEventId(), deadLetters.peek(ADMIN).orElseThrow().event().eventId());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM message", Integer.class));
    }

    @Test
    void malformedDeadLetterIsRetainedAndCannotBypassTrustedOutbox() {
        org.springframework.amqp.core.MessageProperties metadata = new org.springframework.amqp.core.MessageProperties();
        metadata.setContentType("application/json");
        metadata.setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
        template.send(properties.getNotificationDeadLetterExchange(), properties.getNotificationDeadLetterRoutingKey(),
                new Message("{invalid json".getBytes(java.nio.charset.StandardCharsets.UTF_8), metadata));
        await(() -> queueDepth(properties.getNotificationDeadLetterQueue()) == 1);
        var head = deadLetters.peek(ADMIN).orElseThrow();
        assertNull(head.event());
        assertNotNull(head.error());
        await(() -> queueDepth(properties.getNotificationDeadLetterQueue()) == 1);
        assertThrows(BizException.class, () -> deadLetters.replay(ADMIN, new DeadLetterReplayRequest("selected-event", "inspect invalid json")));
        await(() -> queueDepth(properties.getNotificationDeadLetterQueue()) == 1);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM admin_operation_log", Integer.class));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CAMPUS_C3_BROKER_CONTROL", matches = "true")
    void brokerProcessStopAndRestartRecoversPendingEvent() throws Exception {
        var event = event();
        String container = System.getenv().getOrDefault("CAMPUS_C3_RABBIT_CONTAINER", "campus-highlights-rabbit");
        assertEquals(0, new ProcessBuilder("docker", "stop", container).redirectErrorStream(true).start().waitFor());
        try {
            connection.resetConnection();
            assertEquals(0, dispatcher.publishPending());
            assertEquals(OutboxStatus.PENDING, repository.findByEventId(event.getEventId()).orElseThrow().getStatus());
        } finally {
            assertEquals(0, new ProcessBuilder("docker", "start", container).redirectErrorStream(true).start().waitFor());
        }
        await(() -> {
            try { connection.createConnection().close(); return true; } catch (RuntimeException unavailable) { return false; }
        });
        jdbc.update("UPDATE notification_outbox SET next_retry_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE event_id=?", event.getEventId());
        assertEquals(1, dispatcher.publishPending());
        consumeOne();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM message WHERE event_id=?", Integer.class, event.getEventId()));
    }

    private NotificationOutboxEvent event() { return enqueue.enqueue(101L, "TEST", "notification", "test content", 1001L); }

    private void consumeOne() {
        template.execute(channel -> {
            var received = channel.basicGet(properties.getNotificationQueue(), false);
            assertNotNull(received);
            new RabbitNotificationListener(consumer, publisher, properties, metrics).onMessage(decode(received), message(received), channel);
            return null;
        });
    }

    private static Message message(GetResponse received) {
        return new Message(received.getBody(), new DefaultMessagePropertiesConverter()
                .toMessageProperties(received.getProps(), received.getEnvelope(), "UTF-8"));
    }

    private static NotificationEventPayload decode(GetResponse received) {
        return (NotificationEventPayload) template.getMessageConverter().fromMessage(message(received));
    }

    private static int queueDepth(String queue) {
        return ((Number) rabbitAdmin.getQueueProperties(queue).get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue();
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try { Thread.sleep(100); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); fail(interrupted); }
        }
        assertTrue(condition.getAsBoolean(), "Condition not met within 30 seconds");
    }
}
