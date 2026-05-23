# Redis 缓存设计

## 目标

商品详情页和热门榜单都是典型读多写少场景。这个模块用于降低数据库读压力，并让项目具备面试中常问的缓存设计点：旁路缓存、空值缓存、随机过期、缓存失效、热门榜单。

当前项目默认使用内存缓存实现，启动 `redis` profile 后切换为 Redis 实现：

```bash
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=redis"
```

## 商品详情缓存

Key：

```text
item:detail:{itemId}
```

Value：

```json
{
  "exists": true,
  "item": {
    "id": 2001,
    "title": "iPad Air 5 64G",
    "status": "ON_SALE"
  }
}
```

不存在商品会缓存空值：

```json
{
  "exists": false,
  "item": null
}
```

TTL：

```text
正常商品：1800 秒 + 0-300 秒随机抖动
空值缓存：120 秒 + 0-300 秒随机抖动
```

## 读流程

```text
查缓存
-> 命中正常商品：记录浏览热度，返回
-> 命中空值：直接返回 404
-> 未命中：查仓储/数据库
   -> 数据不存在：写空值缓存，返回 404
   -> 数据存在：记录浏览热度，写详情缓存，返回
```

## 写流程

商品被下架、预约锁定、恢复在售、交易完成时，不更新缓存，而是删除缓存：

```text
商品状态变更 -> 更新数据库 -> 删除 item:detail:{itemId}
```

这样避免复杂的双写一致性问题。短时间内最多出现一次缓存未命中，下一次查询会回源并重建缓存。

## 热门商品榜单

Key：

```text
item:hot:rank
```

Redis 类型：

```text
ZSet
```

写入：

```text
ZINCRBY item:hot:rank 1 {itemId}
```

查询：

```text
ZREVRANGE item:hot:rank 0 9
```

业务接口：

```http
GET /api/items/hot?limit=10
```

## 超时订单 ZSet

订单超时队列也复用了 Redis ZSet 思路：

```text
key: order:timeout:zset
score: expireAt 时间戳
value: orderId
```

定时任务通过 score 拉取已到期订单 ID，再回查数据库做状态校验和条件更新，避免扫全表。

## 缓存问题准备

缓存穿透：

```text
对不存在的商品 ID 缓存空值，避免恶意请求反复打到数据库。
```

缓存雪崩：

```text
详情缓存 TTL 增加随机抖动，避免大量 key 同一时间失效。
```

缓存击穿：

```text
当前版本可通过短 TTL + 热门榜单识别热点商品；进阶版可以对热点 key 加互斥锁或逻辑过期。
```

缓存一致性：

```text
采用 Cache Aside 模式。更新数据库后删除缓存，不做缓存和数据库双写。
```
