# 生产安全自检

`prod` profile 下应用会在启动时执行生产安全自检。它的目标是拦住最常见、也最危险的误上线方式：直接拿示例密码、空密码或演示配置对公网启动服务。

## 默认会检查什么

当前自检会拒绝以下配置：

- `app.demo.seed-data=true`：生产环境不能初始化演示账号和演示商品。
- `mysql` profile 下数据库密码为空、过短或仍是 `change_me`、`campus123` 等占位值。
- `redis` profile 下 Redis 密码为空、过短或仍是占位值。
- `rabbitmq` profile 下 RabbitMQ 密码为空、过短、`guest` 或仍是占位值。
- `APP_CORS_ALLOWED_ORIGINS` 里仍包含 `example.com`。
- `APP_STORAGE_TYPE=s3` 时，S3 access key / secret key 为空或仍是占位值。
- 首次管理员 bootstrap 开启时，管理员密码为空、过短或仍是占位值。

失败时应用会直接启动失败，并输出类似：

```text
Production readiness validation failed: MySQL application password must be replaced...
```

这不是 bug，是上线前的保险丝。

## 正式部署前必须替换

从 `.env.prod.example` 复制 `.env.prod` 后，至少替换：

```text
APP_ENV_FILE
MYSQL_PASSWORD
MYSQL_ROOT_PASSWORD
REDIS_PASSWORD
RABBITMQ_PASSWORD
RABBITMQ_DEFAULT_PASS
APP_CORS_ALLOWED_ORIGINS
DOMAIN
```

`APP_ENV_FILE` 默认为 `.env.prod`，用于告诉 Docker Compose 给 app 容器加载哪一份运行时环境变量。正式服务器通常保持 `.env.prod` 即可；本地 smoke 可以指向临时 env 文件。

如果启用管理员 bootstrap，还必须替换：

```text
APP_ADMIN_BOOTSTRAP_PASSWORD
```

如果启用 S3/MinIO/云对象存储，还必须替换：

```text
APP_STORAGE_S3_ACCESS_KEY
APP_STORAGE_S3_SECRET_KEY
APP_STORAGE_PUBLIC_BASE_URL
```

## 本地 smoke 的临时例外

如果只是本机临时验证 compose，并且你明确知道不会对公网暴露，可以临时设置：

```text
APP_PRODUCTION_READINESS_ENABLED=false
```

正式部署必须保持：

```text
APP_PRODUCTION_READINESS_ENABLED=true
```

不要为了省事在真实服务器上关闭这项检查。
