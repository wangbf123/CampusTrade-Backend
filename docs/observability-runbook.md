# 可观测性运行手册

这份手册用于生产上线后的基础监控、日志排障和告警配置。它覆盖当前后端已经内置的 Actuator、Prometheus 指标、请求追踪和 outbox 积压观测能力。

## 1. 暴露端点

生产 profile 已暴露以下 Actuator 端点：

```text
GET /actuator/health
GET /actuator/metrics
GET /actuator/prometheus
```

`/actuator/health` 用于负载均衡和存活探测；`/actuator/prometheus` 用于 Prometheus 抓取。Swagger 在生产环境默认关闭，不应对公网暴露。

## 2. Prometheus 抓取示例

如果 Prometheus 与应用在同一个 Docker 网络内，可直接抓取 Spring Boot 应用容器：

```yaml
scrape_configs:
  - job_name: campustrade-backend
    metrics_path: /actuator/prometheus
    static_configs:
      - targets:
          - app:8080
```

如果 Prometheus 从公网或内网域名通过 Nginx 访问，可改成：

```yaml
scrape_configs:
  - job_name: campustrade-backend
    metrics_path: /actuator/prometheus
    scheme: https
    static_configs:
      - targets:
          - api.your-domain.com
```

生产 Nginx 模板默认只公开 `/actuator/health`，`/actuator/metrics` 和 `/actuator/prometheus` 需要命中 `ACTUATOR_ALLOW_CIDR` 才能访问，其它 `/actuator/**` 默认返回 404。建议优先让 Prometheus 走 Docker 内网 `app:8080` 抓取；如果必须走域名，请把 `ACTUATOR_ALLOW_CIDR` 改成 Prometheus 所在出口 IP 或内网 CIDR，不要无保护地暴露给公网。

## 3. 关键指标

Spring Boot Actuator 会自动提供 JVM、HTTP、Tomcat、系统资源等指标。上线后至少关注：

- `up`：Prometheus 抓取目标是否存活。
- `http_server_requests_seconds_count`：接口请求量。
- `http_server_requests_seconds_sum` / histogram：接口耗时。
- `jvm_memory_used_bytes`、`jvm_memory_max_bytes`：JVM 内存使用。
- `process_cpu_usage`、`system_cpu_usage`：进程和系统 CPU。
- `tomcat_threads_busy_threads`、`tomcat_threads_current_threads`：Tomcat 线程池压力。
- `hikaricp_connections_active`、`hikaricp_connections_pending`：数据库连接池压力，仅 mysql profile 有意义。

本项目额外增加了通知 outbox 指标：

- `campustrade_outbox_pending`：待发布 outbox 事件总数。
- `campustrade_outbox_pending_due`：已经到期、可被发布任务处理的待发布事件数。
- `campustrade_outbox_failed`：达到最大重试次数后失败的事件数。

`campustrade_outbox_pending_due` 持续升高通常表示 RabbitMQ、消费者、数据库或定时任务存在异常；`campustrade_outbox_failed` 大于 0 需要人工排查。

生产 Docker Compose 中 Redis 会读取 `REDIS_PASSWORD` 并启用 `requirepass`；应用会等 MySQL、Redis、RabbitMQ 健康后再启动。Prometheus 告警里可以把 Redis/RabbitMQ/MySQL 容器健康状态也纳入同一组服务可用性检查。

## 4. 建议告警

第一版生产环境建议至少配置这些告警：

- 服务不可用：`up{job="campustrade-backend"} == 0` 持续 1 分钟。
- 健康检查失败：`/actuator/health` 非 200，持续 1 分钟。
- 5xx 错误率升高：5 分钟窗口内 5xx 占比超过 2%。
- 接口慢请求：核心接口 p95 超过 1 秒，持续 5 分钟。
- outbox 堵塞：`campustrade_outbox_pending_due > 100` 持续 5 分钟。
- outbox 失败：`campustrade_outbox_failed > 0`。
- 数据库连接池等待：`hikaricp_connections_pending > 0` 持续 2 分钟。
- JVM 内存压力：堆使用率超过 85% 持续 5 分钟。
- 磁盘空间：MySQL、Redis、上传目录所在磁盘使用率超过 80%。

JLU 全校规模前，建议进一步把 RabbitMQ 队列 ready/unacked 数、Redis 内存、MySQL 慢查询和主机磁盘 I/O 纳入统一监控。

## 5. 请求追踪与慢请求日志

应用会为每个请求设置响应头：

```text
X-Request-Id: <request-id>
```

处理逻辑：

- 如果客户端或 Nginx 传入安全格式的 `X-Request-Id`，应用会复用。
- 如果没有传入，应用会生成 UUID。
- 生产日志格式会输出 requestId，便于从用户报错、Nginx 访问日志、应用日志之间串起来排查。

Nginx 模板会优先透传客户端 `X-Request-Id`，没有时用 Nginx `$request_id` 写入上游请求头。

慢请求日志默认开启，阈值 1000 ms：

```text
APP_OBSERVABILITY_SLOW_REQUEST_ENABLED=true
APP_OBSERVABILITY_SLOW_REQUEST_THRESHOLD_MS=1000
ACTUATOR_ALLOW_CIDR=127.0.0.1/32
APP_OBSERVABILITY_REQUEST_ID_HEADER=X-Request-Id
```

慢请求日志会包含 method、uri、status、durationMs、requestId。上线初期可以先保持 1000 ms；压测和真实流量稳定后，再按核心接口 SLA 调整。

## 6. 上线验收

部署完成后执行：

```bash
curl -fsS https://api.your-domain.com/actuator/health
curl -fsS https://api.your-domain.com/actuator/prometheus | head
curl -I https://api.your-domain.com/api/items
```

验收标准：

- health 返回 `UP`。
- prometheus 输出包含 JVM/HTTP 指标。
- prometheus 输出包含 `campustrade_outbox_pending`、`campustrade_outbox_pending_due`、`campustrade_outbox_failed`。
- 任意 API 响应头包含 `X-Request-Id`。
- 应用日志中包含同一个 requestId。

如果 `/actuator/prometheus` 要通过 Nginx 对外访问，请务必配置 `ACTUATOR_ALLOW_CIDR`、安全组或 VPN；默认配置不会向公网开放 metrics/prometheus。
