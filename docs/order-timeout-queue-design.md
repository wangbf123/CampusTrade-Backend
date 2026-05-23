# 超时订单 Redis ZSet 设计

## 业务场景

买家发起预约后，如果卖家长时间不处理，系统需要自动取消预约，避免订单一直停留在 `PENDING`。

初版可以直接定时扫描数据库：

```sql
SELECT * FROM trade_order
WHERE status = 'PENDING'
  AND expire_at <= NOW();
```

这个方案简单，但订单量变大后会带来周期性扫表压力。当前版本把它升级为超时队列。

## 当前实现

抽象接口：

```text
OrderTimeoutQueue
```

默认实现：

```text
InMemoryOrderTimeoutQueue
```

Redis profile 实现：

```text
RedisOrderTimeoutQueue
```

Redis Key：

```text
order:timeout:zset
```

ZSet 设计：

```text
score = expireAt 时间戳
value = orderId
```

## 写入时机

创建预约订单：

```text
生成 PENDING 订单
-> 保存订单
-> 写入 timeout queue
```

对应 Redis 命令：

```text
ZADD order:timeout:zset {expireAtMillis} {orderId}
```

## 删除时机

订单不再需要超时处理时移除：

```text
卖家确认 PENDING -> CONFIRMED
卖家拒绝 PENDING -> REJECTED
用户取消 PENDING/CONFIRMED -> CANCELLED
交易完成 CONFIRMED -> COMPLETED
超时取消 PENDING -> EXPIRED
```

对应 Redis 命令：

```text
ZREM order:timeout:zset {orderId}
```

## 定时任务

定时任务每轮只拉取已经到期的订单 ID：

```text
ZRANGEBYSCORE order:timeout:zset 0 now LIMIT 0 50
```

然后回查订单并校验状态：

```text
查订单
-> 如果不是 PENDING：从队列移除，跳过
-> 如果是 PENDING：条件更新为 EXPIRED
-> 写 Outbox 通知事件
-> 从队列移除
```

## 为什么还要回查数据库

Redis 只负责“提醒哪些订单可能到期”，最终状态仍以 MySQL 为准。

比如订单已经被卖家确认，但 Redis 删除失败或任务重复拉取到了这个 ID，系统回查发现状态不是 `PENDING`，就不会错误取消订单。

## 面试表达

可以这样讲：

> 初版我可以用定时任务扫 `trade_order` 表中 `PENDING` 且 `expire_at <= now` 的订单，但这会产生周期性扫表压力。后续我把待超时订单写入 Redis ZSet，score 是超时时间戳，value 是订单 ID。定时任务每次只拉取当前时间之前的订单 ID，再回查数据库并通过 `WHERE id = ? AND status = 'PENDING'` 条件更新为 `EXPIRED`。Redis 只作为延迟提醒，最终一致性由数据库状态校验保证。

## 生产增强

- 多实例部署时，处理到期订单可以用 Lua 脚本原子拉取并删除，避免多个实例重复处理。
- 也可以用 Redisson `RDelayedQueue` 或 RabbitMQ 延迟队列替代 ZSet。
- 对超时取消动作记录 `order_event`，方便后台排查。
