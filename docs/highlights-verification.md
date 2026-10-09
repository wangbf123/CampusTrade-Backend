# 交易系统亮点与验收记录

日期：2026-10-09。Java 21、MySQL 8.4.11、Redis 7.4.11、RabbitMQ 3.13；测试使用隔离数据库、唯一队列和专用 broker。

| 亮点 | 关键机制 | 验证 |
|---|---|---|
| 交易一致性 | 事务状态 CAS、reserved_order_id、商品→订单锁顺序、数据库期限检查 | 24 订单争用一个商品只确认一个；取消/完成竞争与事务回滚保持状态一致 |
| 请求结果幂等 | 用户范围唯一请求键、请求摘要、原响应快照与业务同事务保存 | 24 个同键请求只创建一个订单与 Outbox；同键不同载荷冲突；后续状态改变仍重放原响应 |
| 超时故障恢复 | Redis pending/processing ZSet、Lua token、租约回收、MySQL keyset 补扫 | 实际子 JVM 在领取后和提交后 ACK 前 SIGKILL；迟到 ACK、队列丢失与 Redis 不可用恢复 |
| 可靠通知 | SKIP LOCKED 租约领取、Confirm/Returns、抖动退避、事务消费去重、审计重放 | 真 broker stop/start、两份消息一条通知、Return 重试耗尽、DLQ 重放及审计失败回滚 |

启用所有真实依赖测试和专用 broker 控制后，`mvn verify` **115 项测试通过，0 失败、0 错误、0 跳过**，应用 JAR 构建通过。并发数为正确性实验规模；未测生产 QPS、p99 或集群高可用。

对应复现入口和迁移说明：

- [交易幂等与预占](trade-consistency-and-idempotency.md)
- [超时队列设计](order-timeout-queue-design.md)及[实际故障数据](experiments/order-timeout-recovery-results.json)
- [通知链路与专用 broker 故障测试](reliable-notifications.md)

简历可写：

- 基于 MySQL 条件更新、预占归属和短事务实现交易状态一致性与持久请求结果幂等，验证 24 并发争用和同键重试下不重复确认、创建订单。
- 设计 Redis ZSet/Lua 超时任务领取、token ACK 和租约回收协议，结合数据库补偿，在真实进程中断和队列丢失实验中恢复到期订单。
- 实现事务 Outbox、多实例租约发布、RabbitMQ 确认与消费幂等，支持失败事件及死信审计重放，通过真实中间件故障实验验证重复投递不重复落库。

历史 DATETIME 转为 UTC 前需核对旧数据语义；RabbitMQ v2 队列不能原地改为 quorum，需排空并处理原 DLQ。内存 profile 的幂等记录和业务状态不提供进程重启持久保证。
