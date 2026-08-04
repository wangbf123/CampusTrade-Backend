# CampusTrade 服务器部署手册

这份文档用于把本地已经跑通的生产配置，部署到一台真实的 VPS / 云服务器 / ECS 上。

## 1. 服务器建议

小规模试运行或第一版线上环境，最低建议：

- Ubuntu 22.04 或 Ubuntu 24.04 LTS
- 2 核 CPU / 4 GB 内存
- 40 GB 磁盘
- 具备公网 IP
- 安全组开放 `22`、`80`、`443`

如果后续面向更多校园用户开放，建议把 MySQL、Redis、对象存储迁移到云厂商托管服务。当前单机 Docker Compose 部署适合作为第一版线上环境和小规模试运行方案。

## 2. 准备域名

在域名 DNS 控制台添加 A 记录：

```text
api.your-domain.com -> 你的服务器公网 IP
```

等待 DNS 生效后检查解析结果：

```bash
nslookup api.your-domain.com
```

## 3. 初始化 Ubuntu 服务器

把项目上传到服务器，或者在服务器上 clone 项目，然后执行：

```bash
sudo bash deploy/server/bootstrap-ubuntu.sh
```

如果服务器访问 Docker Hub 较慢，可以在执行脚本前配置镜像源：

```bash
sudo DOCKER_REGISTRY_MIRRORS="https://mirror1.example.com,https://mirror2.example.com" \
  bash deploy/server/bootstrap-ubuntu.sh
```

默认不会主动开启 UFW 防火墙。如果希望脚本顺便开启 UFW：

```bash
sudo ENABLE_UFW=true SSH_PORT=22 bash deploy/server/bootstrap-ubuntu.sh
```

## 4. 生成并上传发布包

在本地项目目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\create-release-package.ps1
```

脚本会在 `dist/` 目录生成类似下面的发布包：

```text
campustrade-release-20260529-180000.zip
```

上传到服务器：

```bash
scp dist/campustrade-release-20260529-180000.zip root@your_server_ip:/opt/campustrade/
```

在服务器解压：

```bash
cd /opt/campustrade
unzip campustrade-release-*.zip -d current
cd current
```

## 5. 创建生产环境变量

```bash
cp .env.prod.example .env.prod
```

至少修改这些配置：

```text
MYSQL_PASSWORD
MYSQL_ROOT_PASSWORD
RABBITMQ_PASSWORD
RABBITMQ_DEFAULT_PASS
DOMAIN
```

如果这是第一套生产库且没有管理员账号，可以首次部署时临时打开：

```text
APP_ADMIN_BOOTSTRAP_ENABLED=true
APP_ADMIN_BOOTSTRAP_USERNAME=admin
APP_ADMIN_BOOTSTRAP_PASSWORD=一段至少12位的强密码
```

确认能登录管理员账号后，把 `APP_ADMIN_BOOTSTRAP_ENABLED` 改回 `false` 并重启应用。

如果要限制外部用户注册，打开邀请码准入：

```text
APP_REGISTRATION_INVITE_CODE_REQUIRED=true
```

然后用管理员接口创建邀请码，再让灰度用户使用邀请码注册。

第一次使用 HTTP 部署时，`DOMAIN` 改成你的真实域名：

```text
DOMAIN=api.your-domain.com
```

图片存储默认使用本地持久化卷：

```text
APP_STORAGE_TYPE=local
APP_STORAGE_IMAGE_DIR=/app/uploads/images
APP_STORAGE_PUBLIC_BASE_PATH=/uploads/images
```

如果切换到 MinIO、OSS、COS 等 S3 兼容对象存储，至少修改：

```text
APP_STORAGE_TYPE=s3
APP_STORAGE_S3_ENDPOINT=http://minio:9000
APP_STORAGE_S3_BUCKET=campustrade
APP_STORAGE_S3_ACCESS_KEY=your_access_key
APP_STORAGE_S3_SECRET_KEY=your_secret_key
APP_STORAGE_PUBLIC_BASE_URL=https://cdn.your-domain.com/campustrade
```

注意：`APP_STORAGE_S3_ENDPOINT` 是后端上传使用的内网或服务端地址；`APP_STORAGE_PUBLIC_BASE_URL` 是前端用户访问图片用的公开地址，生产环境建议接 CDN。

## 6. 先用 HTTP 启动

默认生产部署使用 HTTP Nginx 模板：

```text
deploy/nginx/templates/http.conf.template
```

启动服务：

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
docker compose --env-file .env.prod -f docker-compose.prod.yml ps
```

说明：

- MySQL 容器只负责创建空数据库和持久化数据卷。
- 表结构由应用启动时的 Flyway 迁移脚本管理，当前初始脚本为 `src/main/resources/db/migration/V1__init_schema.sql`。
- 如果数据库已经有旧表但没有 Flyway 版本表，`baseline-on-migrate` 会把现有 schema 标记为已接管；接入前仍然要先做一次 MySQL 备份。

检查健康状态：

```bash
curl -fsS http://api.your-domain.com/actuator/health
```

## 7. 执行上线验收

可以在本地电脑执行，也可以在服务器执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\smoke-prod.ps1 -BaseUrl http://api.your-domain.com
```

如果开启了邀请码注册，给 smoke 脚本传入一个剩余次数足够的邀请码：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\smoke-prod.ps1 -BaseUrl http://api.your-domain.com -InviteCode JLU2026
```

验收脚本会覆盖：

- 健康检查接口
- 用户注册 / 登录
- 图片上传
- 商品创建
- 商品详情与热门榜单
- 发起预约
- 卖家确认预约
- RabbitMQ 异步通知
- 完成交易

## 8. 切换 HTTPS

准备证书文件：

```text
deploy/nginx/certs/fullchain.pem
deploy/nginx/certs/privkey.pem
```

然后修改 `docker-compose.prod.yml` 中的 Nginx 模板挂载，把 HTTP 模板切换为 HTTPS 模板：

```yaml
- ./deploy/nginx/templates/https.conf.template:/etc/nginx/templates/default.conf.template:ro
```

重启 Nginx：

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d nginx
```

验证 HTTPS：

```bash
curl -fsS https://api.your-domain.com/actuator/health
```

## 9. 数据库备份

升级、改表、回滚前先备份：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\backup-prod-mysql.ps1
```

如果在 Linux 服务器上直接备份：

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml exec -T mysql \
  sh -lc 'exec mysqldump -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" --single-transaction --quick "$MYSQL_DATABASE"' \
  > "campustrade-$(date +%Y%m%d-%H%M%S).sql"
```

## 10. 版本升级

本地重新打包：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\create-release-package.ps1
```

服务器上更新应用：

```bash
cd /opt/campustrade/current
docker compose --env-file .env.prod -f docker-compose.prod.yml logs app --tail=200
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build app
```

如果版本包含新的数据库迁移脚本，应用启动时会先执行 Flyway migration，再继续启动业务 Bean。升级前务必完成数据库备份；迁移失败时不要继续接入写流量。

升级完成后重新执行 smoke 验收脚本。

## 11. 回滚方案

如果应用版本有问题，但数据库数据正常：

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml stop app
cd /opt/campustrade/last-stable
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d app
```

如果数据迁移或误操作导致数据异常：

1. 先停止写流量。
2. 备份当前异常状态，方便之后排查。
3. 恢复最近一次确认可用的 SQL 备份。
4. 重新执行 smoke 验收。

## 12. 首次上线风险

- Docker Hub 可能访问失败，需要配置 Docker 镜像源，或者提前拉取镜像。
- RabbitMQ 队列参数不能原地修改，本项目的通知队列已使用版本化队列名，降低旧队列定义冲突风险。
- 不要把 MySQL、Redis、RabbitMQ 端口暴露到公网。
- 默认本地文件上传适合第一版上线；真实用户增多前建议把 `APP_STORAGE_TYPE` 切到 `s3`，迁移到 MinIO 或云对象存储，并配合 CDN。
- 正式对外推广前，需要补监控告警、日志留存、后台审核和风控能力。
