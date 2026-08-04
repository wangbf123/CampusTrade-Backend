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

定时任务每轮只拉取已经到期的订单 ID。Redis 实现使用 Lua 脚本把“查询到期订单”和“从 ZSet 删除订单”放在 Redis 内部原子执行，避免多实例定时任务同时拉到同一批订单。

```text
ZRANGEBYSCORE order:timeout:zset -inf now LIMIT 0 50
ZREM order:timeout:zset {orderIds}
```

然后回查订单并校验状态：

```text
查订单
-> 如果不是 PENDING：从队列移除，跳过
-> 如果是 PENDING：条件更新为 EXPIRED
-> 写 Outbox 通知事件
```

## 为什么还要回查数据库

Redis 只负责“提醒哪些订单可能到期”，最终状态仍以 MySQL 为准。

比如订单已经被卖家确认，但 Redis 删除失败或任务重复拉取到了这个 ID，系统回查发现状态不是 `PENDING`，就不会错误取消订单。

## 方案总结

直接定时扫描 `trade_order` 表中 `PENDING` 且 `expire_at <= now` 的订单会产生周期性扫表压力。当前方案把待超时订单写入 Redis ZSet，score 是超时时间戳，value 是订单 ID；定时任务通过 Lua 脚本原子拉取并删除到期订单，再回查数据库并使用 `WHERE id = ? AND status = 'PENDING'` 条件更新为 `EXPIRED`。Redis 只承担延迟提醒，最终一致性仍由数据库状态校验保证。

## 生产增强

- 也可以用 Redisson `RDelayedQueue` 或 RabbitMQ 延迟队列替代 ZSet。
- 对超时取消动作记录 `order_event`，方便后台排查。
