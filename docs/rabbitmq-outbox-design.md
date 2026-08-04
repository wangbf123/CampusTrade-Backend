# RabbitMQ + Outbox 异步通知设计

## 为什么要用 Outbox

订单状态变更后需要通知用户，但通知不应该阻塞核心交易流程。直接在订单服务里调用消息发送会有两个问题：

- 订单更新成功后，消息发送失败，用户收不到通知。
- 消息发送成功后，订单事务回滚，用户收到错误通知。

Outbox 模式的思路是：

```text
订单状态变更
-> 同一个业务流程内写 notification_outbox
-> 后台任务扫描 PENDING 事件
-> 发布到 RabbitMQ
-> 消费者写站内消息表
```

真实 MySQL 落库后，订单状态更新和 outbox 事件写入应放在同一个数据库事务里。

## 当前代码链路

默认本地模式：

```text
TradeOrderService
-> NotificationOutboxService.enqueue(...)
-> notification_outbox: PENDING
-> OutboxPublishTask 定时扫描
-> LocalNotificationPublisher
-> NotificationMessageConsumer
-> MessageService.sendIfAbsent(...)
-> message 表
-> outbox 标记 PUBLISHED
```

RabbitMQ 模式：

```text
OutboxPublishTask
-> RabbitNotificationPublisher
-> DirectExchange
-> Queue
-> RabbitNotificationListener
-> NotificationMessageConsumer
-> message 表
```

## RabbitMQ 配置

Exchange：

```text
campustrade.notification.exchange
```

Queue：

```text
campustrade.notification.queue
```

Routing Key：

```text
notification.created
```

启动 RabbitMQ：

```bash
docker compose up -d rabbitmq
```

启动应用：

```bash
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=rabbitmq"
```

管理后台：

```text
http://localhost:15672
guest / guest
```

## 幂等设计

每条 outbox 事件都有唯一 `eventId`，消费者写站内消息时使用 `eventId` 做幂等键。

MySQL 表设计：

```sql
event_id VARCHAR(64) UNIQUE
```

这样即使 RabbitMQ 因为 ACK 丢失或消费者重试导致重复投递，`MessageService.sendIfAbsent` 也不会写出重复站内消息。

## 失败重试

Outbox 发布失败时：

```text
retry_count + 1
last_error 记录失败原因
next_retry_at 后移
超过 max_retry 后标记 FAILED
```

当前退避策略：

```text
2s、4s、8s、16s、32s，最多 60s
```

## 方案总结

订单接口不会直接同步发送通知，而是采用 Outbox 模式：订单状态变更后先写一条通知事件，由后台任务异步发布。默认本地环境直接消费事件并写入站内消息；RabbitMQ 环境下将事件发布到交换机，再由消费者异步写消息表。消费者通过 `eventId` 唯一键保证幂等，避免 MQ 重复投递导致重复通知。

## 生产增强

后续可以继续增强：

- 开启 RabbitMQ publisher confirm，确认消息到达 broker 后再把 outbox 标记为 `PUBLISHED`。
- 消费失败进入重试队列，超过次数进入死信队列。
- outbox 表按时间归档，避免无限增长。
- 用 `SELECT ... FOR UPDATE SKIP LOCKED` 防止多实例重复扫描同一批事件。
