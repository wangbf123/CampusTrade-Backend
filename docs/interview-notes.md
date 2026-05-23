# CampusTrade 面试回答稿

## 1. 你这个项目是做什么的？

CampusTrade 是一个面向校园闲置交易场景的二手交易与预约履约平台。它不是淘宝式电商，没有优先做购物车、支付和物流，而是围绕校园线下交易流程做商品发布、买家预约、卖家确认、订单状态流转、超时取消、站内通知和热门商品榜单。

项目的后端重点是解决四类问题：

```text
预约订单状态一致性
热门商品访问性能
订单状态变更异步通知
接口刷请求和重复提交
```

## 2. 为什么不做普通商城？

校园二手交易更强调“线下预约履约”，而不是完整电商交易链路。普通商城常见的购物车、支付、物流对这个场景不一定是核心，反而会把项目做散。

所以我把核心放在：

```text
商品发布
预约交易
订单状态机
超时取消
信用和通知
缓存与风控
```

这样业务更贴近学生场景，也更容易引出后端面试常问的状态一致性、缓存、MQ、限流、幂等这些问题。

## 3. 登录鉴权怎么做？

当前演示版使用服务端 token 登录态。用户登录成功后后端生成 token，前端每次请求放在：

```text
Authorization: Bearer <token>
```

后端通过 `AuthInterceptor` 解析 token，把用户 ID、用户名和角色放进 `ThreadLocal` 请求上下文。

真实落库时，我会把 token 登录态存到 Redis。这样退出登录、封禁用户时可以删除 Redis 登录态，实现 token 主动失效。

## 4. 订单状态怎么设计？

订单状态包括：

```text
PENDING      待卖家确认
CONFIRMED    卖家已确认
REJECTED     卖家已拒绝
CANCELLED    用户已取消
EXPIRED      预约超时
COMPLETED    交易完成
```

合法流转：

```text
PENDING -> CONFIRMED
PENDING -> REJECTED
PENDING -> CANCELLED
PENDING -> EXPIRED
CONFIRMED -> COMPLETED
CONFIRMED -> CANCELLED
```

代码里用 `OrderStateMachine` 先校验合法流转，避免业务代码随便改状态。

## 5. 订单状态怎么保证并发下不乱？

状态变更时不直接覆盖字段，而是使用条件更新：

```sql
UPDATE trade_order
SET status = 'CONFIRMED', version = version + 1
WHERE id = ? AND status = 'PENDING';
```

如果影响行数为 0，说明订单已经被其他请求处理过，当前操作失败。这样可以避免重复确认、重复取消、完成后又取消这类状态错乱。

## 6. 多个买家同时预约热门商品怎么办？

项目里发起预约时只生成 `PENDING` 请求，不立刻锁商品。卖家确认某个预约时，通过商品状态条件更新抢占商品：

```sql
UPDATE item
SET status = 'RESERVED', version = version + 1
WHERE id = ? AND status = 'ON_SALE';
```

只有影响行数为 1 的请求能继续把订单改成 `CONFIRMED`。如果影响行数为 0，说明商品已经被预约、售出或下架。

## 7. 为什么用 Redis 缓存商品详情？

商品详情页是读多写少场景，尤其热门商品会被频繁访问。如果每次都查数据库，会给 MySQL 带来压力。

所以我对商品详情使用 Cache Aside 模式：

```text
先查缓存
-> 命中商品：返回
-> 命中空值：返回 404
-> 未命中：查数据库/仓储，存在则写缓存，不存在则写空值缓存
```

商品被下架、预约锁定、恢复在售或售出时，我不更新缓存，而是删除缓存，下一次查询再重建，避免复杂的双写一致性问题。

## 8. 缓存穿透、雪崩、击穿怎么处理？

缓存穿透：

```text
对不存在的商品 ID 写短 TTL 空值缓存，避免恶意请求反复打到数据库。
```

缓存雪崩：

```text
商品详情缓存 TTL 增加随机抖动，避免大量 key 同时过期。
```

缓存击穿：

```text
当前项目通过短 TTL 和热门榜单识别热点商品。进一步优化可以对热点 key 使用互斥锁或逻辑过期。
```

## 9. 热门商品榜单怎么做？

用户访问商品详情时累计商品热度。Redis 模式下使用 ZSet：

```text
key: item:hot:rank
score: 浏览热度
value: itemId
```

浏览详情时：

```text
ZINCRBY item:hot:rank 1 {itemId}
```

查询热门商品：

```text
ZREVRANGE item:hot:rank 0 9
```

## 10. 超时订单怎么处理？

初版可以扫数据库：

```sql
SELECT * FROM trade_order
WHERE status = 'PENDING'
  AND expire_at <= NOW();
```

但这样订单量大了会有周期性扫表压力。所以我抽象了 `OrderTimeoutQueue`，Redis 模式下使用 ZSet：

```text
key: order:timeout:zset
score: expireAt 时间戳
value: orderId
```

创建预约时写入超时队列；卖家确认、拒绝、用户取消、交易完成时移除。定时任务只拉取 `score <= now` 的订单 ID，再回查数据库校验订单是否仍是 `PENDING`，最后通过条件更新改成 `EXPIRED`。

Redis 只做延迟提醒，最终状态以数据库为准。

## 11. 为什么用 RabbitMQ？

订单状态变更后需要通知用户，但通知发送不应该阻塞交易主流程。

比如卖家确认预约，核心流程应该是：

```text
校验订单状态
-> 商品 ON_SALE 改 RESERVED
-> 订单 PENDING 改 CONFIRMED
-> 写通知事件
```

站内消息可以异步生成，不需要让用户等待通知写入完成。

## 12. Outbox 是什么？为什么要用？

如果订单更新成功但 MQ 发送失败，用户就收不到通知。如果 MQ 发送成功但订单事务回滚，用户又会收到错误通知。

Outbox 的思路是：

```text
订单状态变更
-> 写 notification_outbox 事件
-> 后台任务扫描 PENDING 事件
-> 发布到 RabbitMQ
-> 消费者写 message 表
```

真实落库时，订单状态更新和 outbox 事件写入会放在同一个数据库事务里。即使 MQ 暂时不可用，outbox 事件仍然保留为 `PENDING`，后台任务后续继续重试。

## 13. MQ 重复消费怎么办？

每条通知事件都有唯一 `eventId`。消费者写站内消息时使用 `eventId` 做幂等：

```text
message.event_id 唯一索引
```

如果 RabbitMQ 因为 ACK 丢失或网络问题重复投递，消费者再次处理同一个 `eventId` 时不会写出重复消息。

## 14. 接口限流怎么做？

项目里加了 `@RateLimit` 注解。

匿名接口：

```text
登录、注册按 IP 限流
```

登录后接口：

```text
发布商品、创建预约、订单确认/取消/完成按用户 ID 限流
```

Redis 模式下使用固定窗口计数：

```text
INCR rate:{business}:{scope}:{identity}:{window}
如果 count == 1，设置 EXPIRE
如果 count > permits，返回 429
```

## 15. 防重复提交怎么做？

预约创建接口支持：

```text
X-Idempotency-Key
```

如果前端传幂等键，后端使用用户 ID + 幂等键作为唯一请求标识。如果前端没传，就用用户、商品、预约时间、备注生成短期请求指纹。

Redis 模式下使用：

```text
SET idem:appointment:{key} 1 NX EX 300
```

如果设置失败，说明同一个请求已经提交过，返回 409，避免前端重复点击或网络重试导致重复预约。

## 16. 接口慢了怎么排查？

我会先看是哪个阶段慢：

```text
Controller 参数校验
业务逻辑
Redis
MySQL
MQ/Outbox
```

商品详情慢：

```text
看 Redis 命中率、缓存 key、数据库查询耗时。
```

搜索慢：

```text
看组合条件是否命中索引，分页是否过深，是否需要 Elasticsearch。
```

预约慢：

```text
看事务范围、状态更新 SQL、锁竞争、是否有慢 SQL。
```

## 17. 数据库索引怎么设计？

核心索引包括：

```text
item(status, created_at)
item(category, status)
item(seller_id)
trade_order(buyer_id, created_at)
trade_order(seller_id, created_at)
trade_order(status, expire_at)
message(receiver_id, is_read, created_at)
notification_outbox(status, next_retry_at, created_at)
```

这些索引对应商品列表、用户订单列表、超时订单处理、消息列表和 outbox 发布任务。

## 18. 项目还可以怎么优化？

可以从四个方向讲：

```text
数据层：替换内存仓储为 MyBatis-Plus + MySQL，补事务和乐观锁。
搜索层：商品搜索从 MySQL LIKE 演进到 Elasticsearch。
消息层：RabbitMQ 开启 publisher confirm，失败进入死信队列。
风控层：固定窗口限流升级为滑动窗口或令牌桶，登录失败增加验证码或临时冻结。
```

## 19. 最适合简历的一段话

```text
基于 Spring Boot 设计校园二手交易与预约履约平台，完成用户认证、商品发布、交易预约、订单状态流转、站内通知、商品详情缓存和热门商品榜单等模块。系统通过订单状态机限制非法流转，并在确认预约时使用商品状态条件更新保证并发场景下只有一个预约成功；使用 Cache Aside 模式缓存商品详情，对不存在商品写空值缓存，并通过 Redis ZSet 实现热门商品榜单和超时预约队列；基于 RabbitMQ + Outbox 解耦订单状态变更和站内通知，使用 eventId 保证消费者幂等；通过 Redis INCR/EXPIRE 实现接口限流，并用 SET NX EX 防止预约接口重复提交。
```
