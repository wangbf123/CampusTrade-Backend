# C2 订单超时恢复验证

2026-10-09 16:14（Asia/Shanghai），在本云环境实际执行了 20 项测试，20 通过、0 失败、0 错误、0 跳过。机器资源配额为 4 核 / 32 GiB；运行 Java 21、Spring Boot 3.3.7，使用本地实际 MySQL 8.4.11 和 Redis 7.4.11。代码处于未提交工作区，源文件摘要与逐项结果保存在 [JSON](order-timeout-recovery-results.json)。

| 检查 | 故障/竞争 | 验收结果 |
|---|---|---|
| 领取后进程退出 | 子 JVM claim 后实际 SIGKILL | 租约回收后订单 EXPIRED，Outbox 2 条 |
| 提交后 ACK 前退出 | 子 JVM 数据库提交后实际 SIGKILL | 再次领取不新增通知，Outbox 仍为 2 条 |
| 队列全部丢失 | 删除测试独立 namespace 的三键 | MySQL 补扫重建后关闭订单 |
| 提交后漏入队 | 只有已提交 MySQL 订单，无 Redis 项 | 补扫恢复并关闭 |
| Redis 故障 | 领取 API 异常注入；未停止共享 Redis | 有界 MySQL fallback 关闭订单 |
| 提前领取 | 到期时间在未来，队列时间被提前 | 不关闭订单，重新排到数据库截止时间 |
| 旧消费者恢复 | 回收后旧 token ACK/retry | 被拒绝，新领取仍在 |
| 多消费者争抢 | 4 个 worker 竞争 80 个真实 Redis 任务 | 80 个唯一活跃领取，无重复 |
| 补扫公平性 | 最老一页仍在处理中 | 游标继续补齐后续订单 |
| 时区 | Asia/Shanghai 入队，UTC 领取 | UTC 时间戳一致 |

两项 SIGKILL 测试分别耗时 3.325 / 3.541 秒，包含子 JVM 启动和测试夹具成本，不能视为线上恢复延迟。测试租约为 2 秒，生产默认 120 秒。此次验证证明故障恢复与状态/事件幂等性；未做吞吐压测、积压延迟分位统计或高可用实验。

测试覆盖：内存队列 6 项、数据库补偿 3 项、真实 Redis 协议 5 项、MySQL/Redis 业务故障 6 项。所有隔离数据库、Redis namespace 和子进程均由测试清理，不修改现有业务库。

复现：安全注入 `CAMPUS_MYSQL_IT_PASSWORD`，设置 `CAMPUS_MYSQL_IT=true CAMPUS_REDIS_IT=true`，执行：

```bash
mvn -Dtest=InMemoryOrderTimeoutQueueTest,OrderTimeoutRecoveryServiceTest,RedisOrderTimeoutQueueIntegrationTest,MysqlRedisTimeoutRecoveryIntegrationTest test
```

若多个开发进程共享 checkout 的 Maven target，使用 `flock /tmp/campus-maven.lock mvn ...` 串行构建。

已验证后可写简历：

> 设计 Redis ZSet + Lua 的订单超时领取、token ACK 与租约回收协议，结合 MySQL 游标补偿和故障降级处理，通过真实子进程 SIGKILL、队列丢失及并发领取实验验证任务恢复与通知幂等。
