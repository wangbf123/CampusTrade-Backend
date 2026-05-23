# CampusTrade 压测报告

## 说明

当前项目默认使用内存仓储，适合面试现场快速演示；Redis、RabbitMQ 可通过 profile 切换。本文档用于项目展示和面试说明，分为两部分：

- 本地验证结果：用于证明核心链路可运行、状态一致性和幂等逻辑可验证。
- JMeter 正式压测口径：用于后续替换为真实 MySQL/Redis/RabbitMQ 环境后的正式压测报告。

不要在简历中把本地内存模式数据包装成生产性能数据。简历里可以写“使用 JMeter 对核心接口进行压测，并针对缓存、限流和并发预约进行验证”，面试时说明环境和口径。

## 测试环境

当前本地验证环境：

```text
OS: Windows 11
JDK: Java 21
Spring Boot: 3.3.7
运行模式: 默认内存仓储 + 内存缓存 + 本地 Outbox 发布器
启动端口: 18080
```

中间件压测推荐环境：

```text
MySQL 8.x
Redis 7.x
RabbitMQ 3.x
JMeter 5.x
应用独立运行，关闭 IDE Debug
```

## 已验证结果

最近一次自动化验证：

```text
mvn test
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

覆盖内容：

```text
订单状态机合法流转
商品详情缓存与热门商品榜单
Outbox 发布与消费者 eventId 幂等
超时订单队列到期拉取与移除
接口限流计数
预约防重复提交
```

最近一次打包验证：

```text
mvn -DskipTests package
BUILD SUCCESS
```

最近一次启动冒烟：

```text
GET /api/items
响应 code = 0
```

## 本地轻量 Benchmark

这组数据来自默认内存模式，使用本地 Node `fetch` 对已启动的 Spring Boot jar 做轻量并发请求。它用于展示接口链路可用性和本地响应基线，不代表 MySQL/Redis/RabbitMQ 正式压测结果。

测试命令口径：

```text
启动应用：java -jar target/campus-trade-backend-0.1.0-SNAPSHOT.jar --server.port=18080
请求数量：每个接口 100 次
并发数：20
运行模式：默认内存仓储 + 内存缓存 + 本地 Outbox 发布器
```

结果：

| 场景 | 接口 | 请求数 | 并发 | 成功 | 失败 | 平均响应 | P95 | 最大响应 | QPS |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 商品列表 | `GET /api/items` | 100 | 20 | 100 | 0 | 66.33 ms | 152.37 ms | 169.65 ms | 291.85 |
| 商品详情 | `GET /api/items/{id}` | 100 | 20 | 100 | 0 | 49.26 ms | 90.23 ms | 114.95 ms | 385.81 |
| 热门商品 | `GET /api/items/hot?limit=10` | 100 | 20 | 100 | 0 | 44.26 ms | 80.09 ms | 122.39 ms | 417.10 |

本地结论：

```text
默认模式下三个公开查询接口均 100% 成功返回，商品详情访问会累计热门榜单热度，热门商品接口可正常返回榜单数据。
```

## 压测接口

建议压测以下接口：

```text
GET  /api/items/{id}
GET  /api/items/hot?limit=10
POST /api/items/{itemId}/appointments
POST /api/orders/{orderId}/confirm
POST /api/orders/{orderId}/cancel
POST /api/orders/{orderId}/complete
GET  /api/messages
```

## 场景一：商品详情缓存

接口：

```http
GET /api/items/{id}
```

目标：

```text
验证商品详情缓存命中后能降低数据库访问压力。
验证不存在商品 ID 会写入空值缓存，避免缓存穿透。
```

JMeter 配置：

```text
线程数：100
Ramp-Up：10s
循环次数：100
持续时间：3min
断言：HTTP 200 / code = 0
```

结果记录：

```text
未启用 Redis：
平均响应时间：____ ms
P95：____ ms
QPS：____
错误率：____ %

启用 Redis：
平均响应时间：____ ms
P95：____ ms
QPS：____
错误率：____ %
缓存命中率：____ %
```

可写入 README 的结果格式：

```text
商品详情接口在 100 并发、3 分钟压测下，启用 Redis 缓存后平均响应时间由 ____ ms 降低至 ____ ms，P95 由 ____ ms 降低至 ____ ms。
```

## 场景二：热门商品榜单

接口：

```http
GET /api/items/hot?limit=10
```

目标：

```text
验证浏览详情后商品热度能进入榜单。
验证 Redis ZSet 获取热门商品的响应稳定性。
```

JMeter 配置：

```text
先请求 GET /api/items/{id} 1000 次制造热度
再请求 GET /api/items/hot?limit=10
线程数：50
循环次数：100
断言：返回商品列表不为空
```

结果记录：

```text
平均响应时间：____ ms
P95：____ ms
QPS：____
错误率：____ %
```

## 场景三：并发预约确认

接口：

```http
POST /api/orders/{orderId}/confirm
```

目标：

```text
验证同一商品在并发确认场景下只有一个订单能成功变为 CONFIRMED。
验证商品状态只会从 ON_SALE 变为一次 RESERVED。
```

JMeter 配置：

```text
线程数：100
Ramp-Up：1s
循环次数：1
请求目标：同一个商品关联的多个 PENDING 预约
断言：
成功确认数量 = 1
冲突失败数量 = 并发数 - 1
商品最终状态 = RESERVED
```

结果记录：

```text
并发数：100
确认成功：____
冲突失败：____
重复确认：____
商品最终状态：____
```

推荐展示结论：

```text
100 并发确认同一热门商品时，最终仅 1 个订单确认成功，其余请求返回冲突，未出现重复预约成功。
```

## 场景四：Outbox 异步通知

目标：

```text
验证订单状态变更后只写 Outbox 事件。
验证后台任务可以发布 PENDING 事件。
验证消费者按 eventId 幂等写入 message。
```

测试方式：

```text
批量生成 1000 条 PENDING outbox 事件
启动 OutboxPublishTask
观察 message 表 event_id 是否重复
观察 notification_outbox 状态是否从 PENDING 变为 PUBLISHED
```

结果记录：

```text
Outbox 事件数：1000
PUBLISHED 数：____
FAILED 数：____
message 去重后数量：____
重复 eventId 消息数：____
```

推荐展示结论：

```text
批量发布 1000 条通知事件后，Outbox 最终全部发布完成，message 表未出现重复 eventId。
```

## 场景五：超时订单队列

目标：

```text
验证系统不再周期性全表扫描超时订单。
验证 Redis ZSet 只拉取 score <= now 的订单 ID。
```

测试方式：

```text
生成 10000 条待超时订单
其中 1000 条 expireAt <= now
定时任务每批拉取 50 条
验证每轮只处理到期订单 ID
```

结果记录：

```text
总订单数：10000
到期订单数：1000
每批处理数：50
处理轮次：____
误取消订单数：____
非 PENDING 订单被跳过数：____
```

推荐展示结论：

```text
超时任务通过 ZSet 拉取到期订单 ID，再回查数据库校验状态，避免全表扫描和误取消。
```

## 场景六：接口限流

接口：

```http
POST /api/items/{itemId}/appointments
```

限流规则：

```text
同一用户每 60 秒最多 10 次
```

测试方式：

```text
同一用户连续请求 20 次
预期前 10 次进入业务逻辑
后 10 次返回 429
```

结果记录：

```text
请求次数：20
成功/业务响应：____
429 次数：____
错误率：____
```

## 场景七：预约防重复提交

接口：

```http
POST /api/items/{itemId}/appointments
X-Idempotency-Key: test-key-001
```

测试方式：

```text
同一用户、同一商品、同一 X-Idempotency-Key 连续提交 2 次
```

预期结果：

```text
第一次：创建预约成功
第二次：409 检测到重复提交
```

## JMeter 断言建议

统一响应断言：

```json
{
  "code": 0,
  "message": "ok"
}
```

限流响应断言：

```text
HTTP 429
message 包含 请求过于频繁
```

幂等响应断言：

```text
HTTP 409
message 包含 重复提交
```

并发确认断言：

```text
成功响应数量 = 1
冲突响应数量 = N - 1
```

## 简历可写版本

```text
使用 JMeter 对商品详情、热门商品榜单、预约确认、接口限流和防重复提交等核心接口进行压测。针对商品详情读多写少场景引入 Redis 缓存，针对超时订单处理引入 Redis ZSet 队列，针对并发确认使用状态条件更新，验证同一商品在并发确认下仅一个订单成功。
```

## 注意事项

压测报告中的具体数值需要在你最终演示环境中重新跑一遍后填写。不要把内存模式下的本地结果当作 MySQL/Redis/RabbitMQ 真实部署结果。面试时可以主动说明：

```text
我本地有内存模式用于快速演示，也预留了 Redis/RabbitMQ profile。正式压测会使用 MySQL + Redis + RabbitMQ 环境，并分别对缓存命中、并发确认和消息幂等进行验证。
```
