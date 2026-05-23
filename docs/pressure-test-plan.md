# 压测计划

展示版压测报告见 [pressure-test-report.md](pressure-test-report.md)。

推荐用 JMeter 对三个接口压测：

## 商品详情

```text
GET /api/items/{id}
```

目标：

- 对比无缓存和 Redis 缓存后的平均响应时间。
- 观察热点商品详情访问对数据库的压力。

指标：

```text
并发用户：50 / 100 / 200
持续时间：3 分钟
关注：平均响应时间、P95、错误率、QPS
```

## 商品搜索

```text
GET /api/items?keyword=iPad&category=电子产品&minPrice=1000&maxPrice=3000
```

目标：

- 验证组合条件查询的索引效果。
- 对比普通 LIKE 和 Elasticsearch 的差异。

## 预约确认

```text
POST /api/orders/{orderId}/confirm
```

目标：

- 模拟同一商品多个预约被并发确认。
- 验证只有一个订单能变成 `CONFIRMED`，商品只会从 `ON_SALE` 变成一次 `RESERVED`。

建议补充断言：

```text
成功响应数量 = 1
冲突响应数量 = 并发数 - 1
商品最终状态 = RESERVED
```

## README 可展示数据模板

```text
商品详情接口：
未加缓存：平均响应 XX ms，P95 XX ms
加入 Redis 缓存：平均响应 XX ms，P95 XX ms

热门商品接口：
浏览详情 1000 次后，GET /api/items/hot 平均响应 XX ms，P95 XX ms

预约确认接口：
100 并发确认同一商品，成功 1 次，失败 99 次，无重复确认。

Outbox 通知：
批量生成 1000 条 PENDING 事件，后台任务按 batchSize 发布，最终 message 表 event_id 无重复。

超时订单队列：
生成 10000 条待超时订单，只拉取 score <= now 的订单 ID，验证任务不再全表扫描。

接口限流：
同一用户 60 秒内调用预约接口 20 次，预期前 10 次通过，后续返回 429。

防重复提交：
同一用户使用相同 X-Idempotency-Key 连续创建预约，预期第一次成功，第二次返回 409。
```
