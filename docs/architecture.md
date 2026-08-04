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

    Item --> ItemRepo["ItemRepository\n默认内存 / MyBatis-Plus Mapper"]
    Order --> OrderRepo["TradeOrderRepository\n默认内存 / MyBatis-Plus Mapper"]
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

    MySQL[("MySQL\nmysql profile")] -. MyBatis-Plus .-> ItemRepo
    MySQL -. MyBatis-Plus .-> OrderRepo
    MySQL -. MyBatis-Plus .-> MsgRepo
    MySQL -. MyBatis-Plus .-> OutboxRepo
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

## 架构阅读路径

建议先从业务闭环和订单状态机开始，再阅读 Redis 缓存/超时队列以及 RabbitMQ + Outbox 链路，最后查看限流、幂等和生产可观测性。这样的顺序可以先建立领域模型，再理解基础设施如何围绕一致性、可靠性和吞吐量提供支撑。
