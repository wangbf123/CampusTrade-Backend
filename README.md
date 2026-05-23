# CampusTrade Backend

CampusTrade 是一个面向 Java 后端实习面试的校园二手交易与预约履约平台。项目围绕校园闲置物品交易场景，完成商品发布、浏览搜索、预约交易、订单状态流转、超时取消、站内通知、缓存优化、异步解耦、接口限流和防重复提交等后端核心能力。

这个项目不要理解成普通“二手商城”。它的主线是：

```text
校园闲置物品发布
-> 买家预约线下交易
-> 卖家确认/拒绝
-> 订单状态流转
-> 超时自动取消
-> 站内消息通知
-> 热门商品与风控优化
```

## 技术栈

- Java 21
- Spring Boot 3.3.7
- Spring MVC
- Jakarta Validation
- MyBatis-Plus 预留
- MySQL 建表设计
- Redis / Spring Data Redis
- RabbitMQ / Spring AMQP
- springdoc-openapi
- Maven
- Docker Compose

## 项目亮点

- 订单状态机：限制 `PENDING`、`CONFIRMED`、`COMPLETED`、`CANCELLED`、`EXPIRED` 等状态的非法流转。
- 并发预约控制：卖家确认预约时通过商品状态条件更新保证只有一个订单能锁定商品。
- 商品详情缓存：使用 Cache Aside 模式，查询未命中后回源并写缓存，商品状态变更时删除缓存。
- 缓存保护：对不存在商品写空值缓存，TTL 增加随机抖动，降低缓存穿透和雪崩风险。
- 热门商品榜单：浏览商品详情时累计热度，Redis 模式下使用 ZSet 维护热门商品排行。
- 超时订单队列：预约订单写入超时队列，Redis 模式下使用 ZSet 只拉取到期订单 ID，避免全表扫描。
- 异步通知解耦：订单状态变更只写 Outbox 事件，后台任务发布，RabbitMQ 模式下异步消费写站内消息。
- 消费幂等：消息消费者使用 `eventId` 幂等键，避免 MQ 重复投递导致重复通知。
- 接口限流：登录/注册按 IP 限流，预约和订单接口按用户限流。
- 防重复提交：预约创建支持 `X-Idempotency-Key`，Redis 模式下使用 `SET NX EX` 防止重复预约。

## 架构图

核心架构图、订单流程图、缓存/MQ/限流链路图见：

- [架构设计](docs/architecture.md)

## 快速启动

默认内存模式不依赖 MySQL、Redis、RabbitMQ，适合本地快速演示：

```bash
mvn spring-boot:run
```

如果 8080 被占用：

```bash
mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=18080"
```

启动后访问接口文档：

```text
http://localhost:8080/swagger-ui.html
```

演示账号：

```text
seller / 123456
buyer  / 123456
admin  / 123456
```

登录后把返回的 token 放到请求头：

```text
Authorization: Bearer <token>
```

## 中间件模式

启动 Redis：

```bash
docker compose up -d redis
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=redis"
```

启动 RabbitMQ：

```bash
docker compose up -d rabbitmq
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=rabbitmq"
```

同时启用 Redis + RabbitMQ：

```bash
docker compose up -d redis rabbitmq
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=redis,rabbitmq"
```

RabbitMQ 管理后台：

```text
http://localhost:15672
guest / guest
```

## 推荐演示流程

1. `POST /api/auth/login` 登录 `buyer`。
2. `GET /api/items` 查看在售商品。
3. 多次请求 `GET /api/items/{itemId}`，制造浏览热度。
4. `GET /api/items/hot` 查看热门商品榜单。
5. `POST /api/items/{itemId}/appointments` 发起预约，建议带 `X-Idempotency-Key`。
6. `POST /api/auth/login` 登录 `seller`。
7. `GET /api/orders/my-sell` 查看收到的预约。
8. `POST /api/orders/{orderId}/confirm` 确认预约，商品状态变为 `RESERVED`，详情缓存被删除，超时队列移除订单。
9. `POST /api/orders/{orderId}/complete` 完成交易，商品状态变为 `SOLD`。
10. `GET /api/messages` 查看站内通知。

## 项目结构

```text
src/main/java/com/campustrade
├── auth          登录、token、鉴权拦截
├── cache         商品详情缓存、空值缓存、热门榜单、Redis 实现
├── common        统一响应、异常处理、Web 配置、演示数据
├── item          商品发布、搜索、详情、状态控制
├── message       站内消息
├── notification  Outbox、RabbitMQ 发布/消费、通知幂等
├── order         预约订单、状态机、并发确认、超时队列
├── risk          接口限流、防重复提交
├── task          定时任务
└── user          用户、角色、账号状态
```

## 面试材料

- [简历项目描述](docs/resume-description.md)
- [面试回答稿](docs/interview-notes.md)
- [压测报告](docs/pressure-test-report.md)
- [压测计划](docs/pressure-test-plan.md)

## 设计文档

- [架构设计](docs/architecture.md)
- [接口清单](docs/api.md)
- [数据库设计](docs/schema.sql)
- [Redis 缓存设计](docs/redis-cache-design.md)
- [超时订单队列设计](docs/order-timeout-queue-design.md)
- [RabbitMQ + Outbox 设计](docs/rabbitmq-outbox-design.md)
- [接口限流与防重复提交设计](docs/rate-limit-idempotency-design.md)

## 验证命令

```bash
mvn test
mvn -DskipTests package
```

当前测试覆盖：

```text
订单状态机
商品缓存与热门榜单
Outbox 发布与消费者幂等
超时订单队列
限流器
预约防重复提交
```
