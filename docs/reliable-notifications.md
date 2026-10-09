# 可靠通知：至少一次传输与一次业务落库效果

实现日期：2026-10-09。该机制允许网络和 broker 层重复传输，不声称端到端 exactly-once。

## 发布和恢复边界

业务事务写 `notification_outbox`，数据库提交后的短领取事务使用 MySQL 8 的 `FOR UPDATE SKIP LOCKED`。先按租约时间回收过期 PROCESSING，再按到期时间领取 PENDING；两条查询匹配独立索引，采用 READ COMMITTED 避免范围间隙锁阻塞领取。

每次领取生成新 `claim_token`，`lease_until` 由数据库时钟设置。批量发送前逐条续租，避免批次尾部在等待期间过期后仍被旧 owner 发布。远程发布在领取事务之外进行；Confirm ACK 且无 Return 后才附带 token 和未过期条件标记 PUBLISHED。失败采用 1～60 秒有界指数退避与抖动，达到 `app.outbox.max-retry` 后停止自动发布并标记 FAILED。历史失败原因仅保存异常类型分类，避免保存包含账号或 URL 的异常文本。

PUBLISHED 表示 broker 已确认持久接收，不表示消费者已落库。发送成功后数据库标记失败或进程退出，会用同一 event_id 重发。消费者短事务使用数据库唯一 `message.event_id` 与幂等 INSERT；`ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)` 和当前读处理 RR 快照下并发插入不可见的问题。提交后才 ACK。内存 profile 只保证单 JVM 原子操作，不具备重启持久化。

数据库、应用时间按 UTC 配置；mysql profile 的 JDBC/Hikari 会话时区必须落实，只有 serverTimezone 参数不足以修正服务器会话。历史 DATETIME 值的时区需要先核实，迁移 V7 保持历史时间值、只提高列精度，不自动改写已有业务时间。

## 消费重试和死信

数据库连接、瞬时数据访问等可恢复错误：将原始 JSON 持久发布到延迟 retry queue，Confirm 成功后 ACK 原消息；retry 发布不确定时保留原消息并 requeue。队列 TTL 到期后返回主队列，默认最多 3 次，每次 5 秒。不可恢复错误、格式错误或重试耗尽进入 DLQ。

v3 的 main/retry/DLQ 采用 quorum queue；main/retry 设置 `x-dead-letter-strategy=at-least-once` 和 `x-overflow=reject-publish`，避免经典队列默认死信转发的丢失窗口。持久投递的 retry 复制可以产生重复，数据库 event_id 去重提供业务边界。单节点测试只验证协议和进程重启恢复；生产需按 RabbitMQ 官方建议部署 quorum 节点、备份及资源告警，不能据此声称高可用。broker 磁盘损坏等超出单机验证范围。

管理员操作（每个接口都检查角色；POST 每用户每分钟 20 次）：

- `GET /api/admin/notifications/outbox?status=FAILED&limit=20`：最多 100 个，查看失败、待发布或处理中事件。
- `POST /api/admin/notifications/outbox/{eventId}/replay`，JSON `{"reason":"broker restored"}`：仅 FAILED；事务内重置重试次数并写操作审计。
- `GET /api/admin/notifications/dead-letters/head`：查看队首，取出后 requeue；不会删除消息，消息顺序可能变化。
- `POST /api/admin/notifications/dead-letters/replay`，JSON `{"eventId":"selected event","reason":"dependency restored"}`：只重放明确选择的当前队首。内容必须与持久 Outbox 相同。数据库恢复与审计同一事务提交后才 ACK 死信，不直接绕过数据库发消息。

重放保留 event_id、原 id 和业务内容，增加 replay_count。并发变化、原因缺失、非管理员、未知事件或内容不一致均拒绝。审计失败则数据库恢复回滚并 requeue；提交后 ACK 丢失时允许再次读取同一死信，但不会重置正在执行的任务。非法格式消息保留在 DLQ，需要人工检查；没有“自动清空”接口。已恢复后再次失败仍可能再次进入 DLQ，人工重放不算自动恢复。

## v2 → v3 升级

不能在原 v2 队列原地改成 quorum，也不能仅改名字后遗忘旧积压。建议维护窗口先暂停新业务写与 Outbox 发布，保留旧 v2 consumer 排空主队列，核对 ready/unacked 数量；旧 DLQ 逐条检查并转换为持久 Outbox 重放任务，保留 event_id 和审计。核对通知落库/失败事件总数后切换 v3 consumer 和 publisher，再恢复业务。

另一方案是临时保留旧 v2 与新 v3 consumer，先切新 publisher，旧 consumer 排空后下线；必须另行处理旧 DLQ，核对无剩余 ready/unacked 后才删除旧队列。此文说明升级步骤，不自动删除用户已有队列。升级也需运行 Flyway V7；只能在已知测试数据库重新创建数据库，不能在生产环境重跑或删除历史迁移。

## 监控

`campustrade.outbox.pending`、`.pending.due`、`.processing`、`.lease.expired`、`.failed`、`.oldest.age`（秒）监控任务状态；`.publish{outcome}` 和 `.publish.duration` 记录确认/失败/租约丢失及耗时。`campustrade.notification.consume{outcome}`、`.consume.duration` 记录消费提交、重试和死信；DLQ depth 是缓存观察值，-1 代表未知，`dlq.observed.at` 可判断数据是否陈旧。所有标签均为有限枚举，不使用用户/事件 ID。

## 可复现实验

单元测试覆盖并发领取、过期 owner fencing、重试上限、管理员权限、重放审计与内容校验。实依赖测试 `NotificationReliabilityIntegrationTest` 使用实际生产 Mapper/Repository、Spring 事务代理、Flyway、MySQL 8 与 RabbitMQ 3.13，显式隔离 `campus_c3_*` 测试库和唯一队列名。

```bash
# 测试库必须预先建立，账号仅授权该测试库。密码通过环境变量注入，不写进命令历史。
CAMPUS_C3_IT=true mvn -Dtest=NotificationReliabilityIntegrationTest test
# 可选进程故障实验：仅对专用测试 broker 设置，切勿对生产/共享 broker 执行。
CAMPUS_C3_IT=true CAMPUS_C3_BROKER_CONTROL=true \
CAMPUS_C3_RABBIT_CONTAINER=campus-highlights-rabbit \
mvn -Dtest=NotificationReliabilityIntegrationTest test
```

配置名：CAMPUS_C3_JDBC_URL（默认本地 3307 的 campus_c3_test）、CAMPUS_C3_DB_USER/PASSWORD、CAMPUS_C3_RABBIT_HOST/PORT/USER/PASSWORD。测试删除该隔离库的 Outbox/message/audit 数据，结束删除自己创建的唯一队列，不清空应用队列。测试 broker stop/start 必须单独 opt in；普通 CI 不操作 Docker 进程。

实验：60 个事件双实例领取与后续扫描、过期 token、Confirm 后丢标记产生两份 broker 消息但一条通知、消费提交后物理 channel 关闭及 16 个重复调用、Return 导致精确三次重试与失败重放、实际 DLQ 按 event_id 重放、审计失败回滚、真实延迟队列重试，以及专用 broker 进程 stop/start 后恢复。窗口和队列状态通过显式条件等待核验，不把缓存 channel 的普通 close 冒充进程崩溃。

简历可写：“基于事务 Outbox、RabbitMQ Confirm 与数据库消费幂等构建通知链路，实现多实例租约领取、退避重试和审计死信重放，并通过真实 MySQL/RabbitMQ 故障与重复投递实验验证通知不重复落库。”不要将这些小规模正确性实验写成生产吞吐或高可用指标。
