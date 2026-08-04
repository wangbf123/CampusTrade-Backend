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

## 认证模块

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
  "campus": "东校区",
  "inviteCode": "JLU2026"
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

## 用户模块

### 当前用户

```http
GET /api/users/me
```

## 商品模块

### 上传商品图片

```http
POST /api/files/images
Content-Type: multipart/form-data
```

表单字段：

```text
file=<图片二进制内容>
```

响应示例：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "url": "/uploads/images/20260529/9f4ab4b0e4f54a7f8cbe4f75dd7d0e1d.png",
    "originalFilename": "ipad.png",
    "size": 123456,
    "contentType": "image/png"
  }
}
```

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
GET /api/items?keyword=iPad&category=电子产品&campus=东校区&minPrice=1000&maxPrice=3000&page=1&size=20
```

说明：

- `page` 从 1 开始，默认 1。
- `size` 默认 20，最大 100。

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

## 预约订单模块

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
GET /api/orders/my-buy?page=1&size=20
```

`page` 默认为 `1`，`size` 默认为 `20`，最大 `100`。

### 我的卖出预约

```http
GET /api/orders/my-sell?page=1&size=20
```

`page` 默认为 `1`，`size` 默认为 `20`，最大 `100`。

## 消息模块

### 我的消息

```http
GET /api/messages?page=1&size=20
```

`page` 默认为 `1`，`size` 默认为 `20`，最大 `100`。

### 标记已读

```http
PUT /api/messages/{id}/read
```

## 举报模块

### 创建举报

```http
POST /api/reports
```

```json
{
  "targetType": "ITEM",
  "targetId": 1001,
  "reason": "疑似违规商品",
  "description": "商品描述包含违规内容"
}
```

## 管理员模块

管理员接口需要 `ADMIN` 角色。

### 举报列表

```http
GET /api/admin/reports?status=PENDING&page=1&size=20
```

### 处理举报

```http
POST /api/admin/reports/{reportId}/resolve
```

```json
{
  "status": "RESOLVED",
  "auditResult": "已下架违规商品"
}
```

`status` 可用值：

```text
RESOLVED
REJECTED
```

### 封禁用户

```http
PUT /api/admin/users/{userId}/ban
```

```json
{
  "reason": "多次发布违规商品"
}
```

### 解封用户

```http
PUT /api/admin/users/{userId}/unban
```

```json
{
  "reason": "申诉通过"
}
```

### 强制下架商品

```http
PUT /api/admin/items/{itemId}/off-shelf
```

```json
{
  "reason": "违规商品"
}
```

### 邀请码列表

```http
GET /api/admin/invite-codes?status=ACTIVE&page=1&size=20
```

### 创建邀请码

```http
POST /api/admin/invite-codes
```

```json
{
  "code": "JLU2026",
  "maxUses": 100,
  "expiresAt": "2026-09-01T00:00:00",
  "remark": "吉林大学首批灰度"
}
```

说明：

- `code` 不传时后端会自动生成。
- `maxUses` 不传时默认为 1。

### 撤销邀请码

```http
PUT /api/admin/invite-codes/{code}/revoke
```
