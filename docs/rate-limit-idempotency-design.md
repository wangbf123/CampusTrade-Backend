# 接口限流与防重复提交设计

## 目标

校园交易平台里有几类接口需要保护：

- 登录、注册：防止暴力尝试和短信/账号资源滥用。
- 发布商品：防止短时间刷大量商品。
- 预约接口：防止重复点击、脚本抢占和恶意刷请求。
- 订单状态接口：防止用户频繁确认、取消、完成。

当前实现提供两类能力：

```text
@RateLimit 注解：按 IP 或用户做固定窗口限流
IdempotencyService：预约创建防重复提交
```

默认本地模式使用内存实现，`redis` profile 下使用 Redis 实现。

## 限流设计

注解示例：

```java
@RateLimit(key = "appointment:create", permits = 10, windowSeconds = 60, scope = RateLimitScope.USER)
```

Key 结构：

```text
rate:{businessKey}:{scope}:{identity}:{windowBucket}
```

示例：

```text
rate:appointment:create:USER:1001:29712033
```

Redis 实现思路：

```text
INCR key
如果 count == 1，设置 EXPIRE
如果 count > permits，返回 429
```

当前限流规则：

```text
注册：同一 IP 每分钟 5 次
登录：同一 IP 每分钟 10 次
发布商品：同一用户每分钟 20 次
创建预约：同一用户每分钟 10 次
订单确认/拒绝/取消/完成：同一用户每分钟 30 次
```

## 防重复提交设计

预约创建接口支持请求头：

```text
X-Idempotency-Key: <client-generated-key>
```

如果前端传入该请求头，后端使用：

```text
idem:appointment:header:{userId}:{idempotencyKey}
```

如果前端没有传入请求头，后端使用用户、商品、预约时间、备注生成短期指纹：

```text
idem:appointment:fingerprint:{sha256(userId:itemId:expectedTime:note)}
```

Redis 实现思路：

```text
SET key 1 NX EX 300
```

如果设置失败，说明同一个请求在 TTL 内已经提交过，返回 409。

## 面试表达

可以这样讲：

> 我对登录、注册这种匿名接口按 IP 限流，对发布商品、预约、订单状态变更这种登录后接口按用户 ID 限流。Redis 版本使用 INCR + EXPIRE 实现固定窗口计数，超过阈值返回 429。预约创建接口额外支持 X-Idempotency-Key，后端使用 SET NX EX 保证同一个幂等键在 TTL 内只能提交一次，防止前端重复点击或网络重试导致重复预约。

## 可继续增强

- 用 Lua 脚本保证 `INCR + EXPIRE` 完全原子。
- 将固定窗口改成滑动窗口或令牌桶。
- 幂等服务保存响应摘要，实现重复请求返回同一结果，而不是直接拒绝。
- 对登录失败次数单独计数，连续失败后临时冻结账号或验证码升级。
