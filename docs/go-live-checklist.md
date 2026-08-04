# 上线检查清单

这份清单用于在第一版真实部署前，系统检查配置、数据、安全、可观测性和回滚条件。

## 1. 首次发布前

- 替换 `.env.prod` 中所有占位密码。
- 修改 `DOMAIN`，并确认 Nginx 使用真实域名。
- 修改 `APP_CORS_ALLOWED_ORIGINS`，只填写正式前端/后台域名。
- 将正式 TLS 证书放入 `deploy/nginx/certs/`。
- 首次部署先使用 HTTP Nginx 模板；证书文件准备好后再切换 HTTPS 模板。
- 确认 `SPRING_PROFILES_ACTIVE=prod,mysql,redis,rabbitmq`。
- 确认 `app.demo.seed-data=false`，生产环境不要初始化演示数据。
- 首次生产部署如无管理员账号，临时设置 `APP_ADMIN_BOOTSTRAP_ENABLED=true` 创建管理员；创建成功后改回 `false` 并重启。
- 如果准备灰度开放注册，设置 `APP_REGISTRATION_INVITE_CODE_REQUIRED=true`，先由管理员创建足量邀请码。
- 确认 `mysql` profile 下 Flyway 已启用，首次空库会自动执行 `V1__init_schema.sql`。
- 如果是已有数据库首次接入 Flyway，先备份数据库；应用会用 `baseline-on-migrate` 接管现有 schema。
- 确认至少有一个 `ADMIN` 角色账号，并验证举报处理、用户封禁、商品强制下架能写入管理员操作日志。
- 确认 MySQL、Redis、RabbitMQ 的磁盘空间和重启策略。
- 如果 `APP_STORAGE_TYPE=local`，确认上传目录已经挂载到持久化卷。
- 如果 `APP_STORAGE_TYPE=s3`，确认对象存储 bucket 已创建，`APP_STORAGE_PUBLIC_BASE_URL` 是浏览器可访问的公开/CDN 地址。
- 确认 `/actuator/health` 只通过预期网络路径访问。
- 如果 RabbitMQ 队列参数相对旧版本发生变化，需要使用版本化队列名，或者启动消费者前删除旧队列定义。

## 2. 首次部署

```powershell
Copy-Item .env.prod.example .env.prod -Force
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
docker compose --env-file .env.prod -f docker-compose.prod.yml ps
```

也可以使用辅助脚本：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\start-prod-local.ps1
```

生成服务器发布包：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\create-release-package.ps1
```

## 3. 上线验收

服务健康后执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\smoke-prod.ps1 -BaseUrl http://localhost
```

如果生产环境开启了邀请码注册：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\smoke-prod.ps1 -BaseUrl http://localhost -InviteCode JLU2026
```

验收脚本会检查：

- 健康检查接口
- 注册 / 登录
- 图片上传
- 商品创建
- 商品详情和热门榜单
- 发起预约
- 卖家确认预约
- 异步通知投递
- 完成订单

## 4. 风险操作前备份

改表、升级、回滚前先执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\backup-prod-mysql.ps1
```

后续所有表结构变更都应新增 `src/main/resources/db/migration/V{版本}__{说明}.sql`，不要直接手工改生产库。

## 5. 回滚方案

如果新应用版本异常，但数据仍然正确：

1. `docker compose --env-file .env.prod -f docker-compose.prod.yml logs app --tail=200`
2. `docker compose --env-file .env.prod -f docker-compose.prod.yml stop app`
3. 将应用镜像或 jar 替换为上一个稳定版本。
4. `docker compose --env-file .env.prod -f docker-compose.prod.yml up -d app`
5. 重新执行 `scripts\smoke-prod.ps1`。

如果数据迁移导致数据异常：

1. 停止写流量。
2. 导出当前异常状态，保留排查依据。
3. 恢复最近一次确认可用的备份。
4. 重新执行 smoke 验收。

## 6. 后续运维增强

- 接入日志采集。
- 对 `/actuator/health` 增加监控告警。
- 根据真实慢查询日志复查数据库索引。
- 将 `APP_STORAGE_TYPE` 从 `local` 切换到 `s3`，迁移到 MinIO 或云对象存储。
- 正式公开前继续补实名认证、风控、敏感词和更完整的违规处理流程。
