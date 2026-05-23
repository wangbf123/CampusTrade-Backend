# CampusTrade API 清单

统一响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {}
}
```

鉴权方式：

```text
Authorization: Bearer <token>
```

## Auth

### 注册

```http
POST /api/auth/register
```

```json
{
  "username": "tom",
  "password": "123456",
  "nickname": "Tom",
  "phone": "18800000000",
  "campus": "东校区"
}
```

### 登录

```http
POST /api/auth/login
```

```json
{
  "username": "buyer",
  "password": "123456"
}
```

### 退出登录

```http
POST /api/auth/logout
```

## User

### 当前用户

```http
GET /api/users/me
```

## Item

### 发布商品

```http
POST /api/items
```

```json
{
  "title": "iPad Air 5 64G",
  "description": "自用平板，屏幕无划痕",
  "category": "电子产品",
  "price": 2599.00,
  "conditionLevel": "LIKE_NEW",
  "campus": "东校区",
  "tradePlace": "图书馆一楼",
  "imageUrls": ["https://example.com/ipad.jpg"]
}
```

### 商品列表

```http
GET /api/items?keyword=iPad&category=电子产品&campus=东校区&minPrice=1000&maxPrice=3000
```

### 商品详情

```http
GET /api/items/{id}
```

### 热门商品

```http
GET /api/items/hot?limit=10
```

### 下架商品

```http
PUT /api/items/{id}/off-shelf
```

## Appointment Order

### 发起预约

```http
POST /api/items/{itemId}/appointments
X-Idempotency-Key: 7b25d4a4-xxxx
```

```json
{
  "expectedTime": "2026-05-24T19:30:00",
  "note": "晚上图书馆门口方便吗"
}
```

### 卖家确认

```http
POST /api/orders/{orderId}/confirm
```

### 卖家拒绝

```http
POST /api/orders/{orderId}/reject
```

### 取消预约

```http
POST /api/orders/{orderId}/cancel
```

```json
{
  "reason": "时间不合适"
}
```

### 完成交易

```http
POST /api/orders/{orderId}/complete
```

### 我的买入预约

```http
GET /api/orders/my-buy
```

### 我的卖出预约

```http
GET /api/orders/my-sell
```

## Message

### 我的消息

```http
GET /api/messages
```

### 标记已读

```http
PUT /api/messages/{id}/read
```
