# CampusTrade 架构设计

## 总体架构

```mermaid
flowchart LR
    Client["Client / Swagger / Frontend"] --> API["Spring Boot REST API"]

    API --> Auth["AuthInterceptor\nToken 鉴权"]
    API --> Limit["RateLimitInterceptor\nIP/用户限流"]

    Auth --> Item["ItemService\n商品发布/搜索/详情"]
    Auth --> Order["TradeOrderService\n预约/确认/取消/完成"]
    Auth --> Message["MessageService\n站内消息"]

    Item --> ItemRepo["ItemRepository\n默认内存 / MyBatis-Plus 预留"]
    Order --> OrderRepo["TradeOrderRepository\n默认内存 / MyBatis-Plus 预留"]
    Message --> MsgRepo["MessageRepository\n消息幂等存储"]

    Item --> DetailCache["ItemDetailCache\n商品详情缓存"]
    Item --> HotRank["HotItemRankService\n热门商品榜单"]
    Order --> TimeoutQueue["OrderTimeoutQueue\n超时订单队列"]
    Order --> Outbox["NotificationOutboxService\n写通知事件"]
    API --> Idem["IdempotencyService\n预约防重复提交"]

    DetailCache -. redis profile .-> Redis[("Redis")]
    HotRank -. redis profile .-> Redis
    TimeoutQueue -. redis profile .-> Redis
    Limit -. redis profile .-> Redis
    Idem -. redis profile .-> Redis

    Outbox --> OutboxRepo["NotificationOutboxRepository\nPENDING/PUBLISHED/FAILED"]
    OutboxTask["OutboxPublishTask\n定时发布"] --> OutboxRepo
    OutboxTask --> Publisher["NotificationPublisher"]
    Publisher -. local .-> Consumer["NotificationMessageConsumer"]
    Publisher -. rabbitmq profile .-> MQ[("RabbitMQ")]
    MQ --> RabbitListener["RabbitNotificationListener"]
    RabbitListener --> Consumer
    Consumer --> Message

    MySQL[("MySQL\nschema.sql 设计")] -. mysql profile 预留 .-> ItemRepo
    MySQL -. mysql profile 预留 .-> OrderRepo
    MySQL -. mysql profile 预留 .-> MsgRepo
    MySQL -. mysql profile 预留 .-> OutboxRepo
```

## 核心业务流程

```mermaid
sequenceDiagram
    participant Buyer as Buyer
    participant API as Spring Boot API
    participant Order as TradeOrderService
    participant Item as ItemService
    participant Timeout as OrderTimeoutQueue
    participant Outbox as NotificationOutbox
    participant Seller as Seller

    Buyer->>API: POST /items/{itemId}/appointments
    API->>API: Token 鉴权 + 限流 + 幂等校验
    API->>Order: createAppointment
    Order->>Item: 校验商品 ON_SALE
    Order->>Order: 创建 PENDING 预约单
    Order->>Timeout: 写入超时队列
    Order->>Outbox: 写 APPOINTMENT_CREATED 事件
    Order-->>Buyer: 返回预约单

    Seller->>API: POST /orders/{orderId}/confirm
    API->>Order: confirm
    Order->>Order: 校验 PENDING -> CONFIRMED
    Order->>Item: ON_SALE -> RESERVED 条件更新
    Order->>Timeout: 移除超时队列
    Order->>Outbox: 写 APPOINTMENT_CONFIRMED 事件
    Order-->>Seller: 返回已确认订单
```

## 订单状态机

```mermaid
stateDiagram-v2
    [*] --> PENDING: 买家发起预约
    PENDING --> CONFIRMED: 卖家确认
    PENDING --> REJECTED: 卖家拒绝
    PENDING --> CANCELLED: 买家/卖家取消
    PENDING --> EXPIRED: 超时未处理
    CONFIRMED --> COMPLETED: 交易完成
    CONFIRMED --> CANCELLED: 交易取消
    REJECTED --> [*]
    CANCELLED --> [*]
    EXPIRED --> [*]
    COMPLETED --> [*]
```

状态变更不是直接覆盖字段，而是先由 `OrderStateMachine` 校验，再通过条件更新保证并发安全：

```sql
UPDATE trade_order
SET status = 'CONFIRMED', version = version + 1
WHERE id = ? AND status = 'PENDING';
```

商品预约抢占同理：

```sql
UPDATE item
SET status = 'RESERVED', version = version + 1
WHERE id = ? AND status = 'ON_SALE';
```

## Redis 使用点

```mermaid
flowchart TD
    Redis[("Redis")]
    Detail["item:detail:{itemId}\n商品详情缓存"]
    Hot["item:hot:rank\n热门商品 ZSet"]
    Timeout["order:timeout:zset\n超时订单 ZSet"]
    Rate["rate:{business}:{scope}:{id}:{window}\n接口限流计数"]
    Idem["idem:appointment:{key}\n防重复提交"]

    Redis --> Detail
    Redis --> Hot
    Redis --> Timeout
    Redis --> Rate
    Redis --> Idem
```

## RabbitMQ + Outbox 链路

```mermaid
flowchart LR
    Order["订单状态变更"] --> Outbox["写 notification_outbox\nPENDING"]
    Task["OutboxPublishTask"] --> Scan["扫描 PENDING 事件"]
    Scan --> Publish["发布 NotificationEventPayload"]
    Publish --> MQ[("RabbitMQ DirectExchange")]
    MQ --> Queue["campustrade.notification.queue"]
    Queue --> Listener["RabbitNotificationListener"]
    Listener --> Consumer["NotificationMessageConsumer"]
    Consumer --> Message["message 表\n按 eventId 幂等写入"]
    Publish --> Done["Outbox 标记 PUBLISHED"]
```

Outbox 解决的问题：

- 订单主流程不等待通知发送。
- MQ 临时不可用时，事件保留为 `PENDING`，后台任务继续重试。
- 消费者通过 `eventId` 保证幂等，避免重复通知。

## 限流与防重复提交

```mermaid
flowchart TD
    Request["请求进入"] --> Auth{"是否公开接口"}
    Auth -->|公开| IPLimit["按 IP 限流"]
    Auth -->|登录接口| UserLimit["按用户 ID 限流"]
    IPLimit --> Controller["Controller"]
    UserLimit --> Controller
    Controller --> Appointment{"创建预约?"}
    Appointment -->|是| Idem["X-Idempotency-Key\n或请求指纹\nSET NX EX"]
    Appointment -->|否| Service["业务服务"]
    Idem --> Service
```

## 面试讲解顺序

推荐按这个顺序讲，逻辑最顺：

1. 先讲业务：校园二手交易不是完整电商，核心是线下预约履约。
2. 再讲订单状态机：用状态机限制非法流转，用条件更新解决并发修改。
3. 接着讲 Redis：商品详情缓存、热门商品榜单、超时订单队列。
4. 再讲 MQ：Outbox 解耦订单状态变更和通知发送，eventId 做消费幂等。
5. 最后讲风控：限流和幂等键防刷接口、防重复预约。
