# aifaizhi-data-sync 部署文档

本文档说明 `aifaizhi-data-sync`（SPIC 附件元数据增量同步服务）的三种容器化部署方式、配置覆盖方法以及验证步骤。

- 技术栈：Spring Boot 2.7.18 + Java 8 + PostgreSQL（双数据源）
- 服务端口：`13607`
- 运行模式：常驻 Web 服务（默认）或一次性任务（`--sync`）

---

## 一、交付物清单

本次新增的部署相关文件：

| 文件 | 用途 |
|---|---|
| `Dockerfile` | 多阶段构建镜像（Maven+JDK8 构建 → JRE8 运行，非 root） |
| `.dockerignore` | 构建上下文排除项 |
| `docker-compose.yml` | 生产编排：仅应用服务，连接外部 PG |
| `docker-compose.demo.yml` | 本地演示栈叠加：源/目标 PG + 应用，端到端验证用 |
| `deploy/docker-run.sh` | `docker run` 一键部署脚本 |
| `deploy/docker-run.env.example` | `docker run` 环境变量示例 |
| `deploy/maven-settings.xml` | 构建期阿里云 Maven 镜像配置（加速国内依赖下载） |
| `deploy/config/application-demo.yml` | demo 栈的数据源覆盖配置 |
| `config/application.yml.example` | 外部配置覆盖模板（生产推荐） |
| `deploy/k8s/namespace.yaml` | k8s 命名空间 |
| `deploy/k8s/configmap.yaml` | 非敏感运行配置（挂载为 application.yml） |
| `deploy/k8s/secret.yaml` | 数据库密码（Secret） |
| `deploy/k8s/deployment.yaml` | 工作负载（含探针、资源、配置/密码注入） |
| `deploy/k8s/service.yaml` | Service（ClusterIP:13607） |
| `deploy/k8s/kustomization.yaml` | Kustomize 入口 |

> 为支持容器/k8s 探针，`pom.xml` 新增了 `spring-boot-starter-actuator`，
> `application.yml` 暴露了 `/actuator/health/liveness` 与 `/actuator/health/readiness` 两个可用性探针（不触碰数据库，DB 不可达时也不会阻塞）。

---

## 二、前置条件

| 工具 | 版本要求 | 验证命令 |
|---|---|---|
| Docker | ≥ 20.10 | `docker --version` |
| Docker Compose | ≥ v2（或 v1.29+） | `docker compose version` |
| kubectl | ≥ 1.21（仅 k8s 方式需要） | `kubectl version --client` |
| 数据库 | PostgreSQL 12+ | 源库 `jm_cloud`、目标库 `szsjzzkk` 需预先建好表，见 README「五、部署步骤」 |

> 镜像构建阶段需要访问 Maven 仓库；已内置阿里云镜像加速，国内网络可正常构建。

---

## 三、构建镜像

三种方式都基于同一个镜像，先构建一次：

```bash
docker build -t aifaizhi-data-sync:0.0.1-SNAPSHOT .
```

构建说明：
- **多阶段**：阶段一用 `maven:3.9-eclipse-temurin-8` 打包（JDK 8 编译可规避 Lombok 在高版本 JDK 上的注解处理器问题），阶段二用 `eclipse-temurin:8-jre` 运行，最终镜像约 500MB。
- **BuildKit 缓存**：`--mount=type=cache,target=/root/.m2` 复用宿主机 Maven 仓库，第二次构建几乎不下载依赖。
- **非 root**：运行时用户为 `app`，日志目录 `/app/logs` 已授权。
- **健康检查**：镜像内置 `HEALTHCHECK`，访问 `/actuator/health/liveness`。

如果需要推送到私有仓库：

```bash
docker tag aifaizhi-data-sync:0.0.1-SNAPSHOT registry.example.com/aifaizhi/aifaizhi-data-sync:0.0.1-SNAPSHOT
docker push registry.example.com/aifaizhi/aifaizhi-data-sync:0.0.1-SNAPSHOT
```

---

## 四、配置覆盖

镜像内置了 `application.yml`（含默认数据源地址）。切换环境/修改配置**无需重新构建镜像**，推荐用外部配置覆盖：

### 方式 A：挂载外部 `application.yml`（推荐，生产用）

```bash
cp config/application.yml.example config/application.yml
# 编辑 config/application.yml，填入真实的数据库地址/账号/密码
```

`config/application.yml` 会被挂载到容器 `/app/config/application.yml`，覆盖镜像内置配置。
> `config/application.yml` 含敏感信息，已加入 `.gitignore`，不会被提交。

### 方式 B：环境变量

简单标量配置可用环境变量覆盖（Spring relaxed binding），例如：

| 环境变量 | 对应配置 |
|---|---|
| `SPRING_PROFILES_ACTIVE` | 激活的 profile |
| `SERVER_PORT` | `server.port` |
| `DATA_SYNC_MODE` | `data-sync.mode`（`db` / `api`） |
| `DATA_SYNC_BATCH_SIZE` | `data-sync.batch-size` |
| `DATA_SYNC_SCHEDULE_ENABLED` | `data-sync.schedule-enabled` |
| `DATA_SYNC_CRON` | `data-sync.cron` |
| `JAVA_OPTS` | JVM 参数（覆盖镜像默认值） |

> 数据源 URL/密码这类**含下划线的 Map 键**（`jm_cloud`、`szsjzzkk`），环境变量绑定不可靠，
> 请用「方式 A」外部配置文件覆盖，或用 k8s 的 ConfigMap + Secret 注入（见第七节）。

---

## 五、部署方式一：docker run 脚本

`deploy/docker-run.sh` 封装了构建、端口映射、日志卷、环境变量加载与重启策略。

### 5.1 常驻服务（默认）

```bash
# 镜像不存在会自动构建；已存在则直接启动
./deploy/docker-run.sh
```

### 5.2 常用变体

```bash
# 强制重新构建镜像后启动
./deploy/docker-run.sh --build

# 一次性跑同步任务后退出（脚本会自动关闭重启策略，避免循环重启）
./deploy/docker-run.sh --sync
./deploy/docker-run.sh --sync=2086724333076062209   # 只同步指定 relevant_id

# 自定义端口 / 容器名 / 镜像
PORT=13607 NAME=aifaizhi-data-sync IMAGE=aifaizhi-data-sync:0.0.1-SNAPSHOT ./deploy/docker-run.sh
```

### 5.3 可选：环境变量文件

```bash
cp deploy/docker-run.env.example deploy/docker-run.env
# 编辑后，脚本会自动通过 --env-file 加载（文件不存在则跳过）
```

### 5.4 验证

```bash
docker ps --filter name=aifaizhi-data-sync          # 应显示 (healthy)
docker logs -f aifaizhi-data-sync                    # 查看实时日志
curl http://127.0.0.1:13607/actuator/health/readiness   # 期望 {"status":"UP"}
curl http://127.0.0.1:13607/api/sync/task/status        # 查看任务状态
```

停止并移除：

```bash
docker stop aifaizhi-data-sync && docker rm aifaizhi-data-sync
```

---

## 六、部署方式二：docker-compose

### 6.1 生产编排（连接外部 PG）

```bash
# 1) 准备外部配置（切换数据库地址/账号）
cp config/application.yml.example config/application.yml
#   编辑 config/application.yml

# 2) 构建并后台启动
docker compose up -d --build

# 3) 查看状态与日志
docker compose ps
docker compose logs -f aifaizhi-data-sync
```

`docker-compose.yml` 关键配置：
- 端口映射 `13607:13607`
- 日志卷 `./logs:/app/logs`
- 外部配置目录 `./config:/app/config:ro`（放入 `application.yml` 即覆盖）
- `restart: unless-stopped`
- 内置 `healthcheck`（liveness 探针）

停止（保留数据卷）：

```bash
docker compose down
```

### 6.2 本地端到端验证（demo 叠加栈）

`docker-compose.demo.yml` 会额外拉起**源库 + 目标库**两个 PostgreSQL 容器，自动执行 `init/` 下的建表与测试数据脚本，应用以 `local` profile 连接这两个库，可在无外部数据库的情况下完成端到端验证。

```bash
# 启动完整演示栈
docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --build

# 等待应用健康
docker compose -f docker-compose.yml -f docker-compose.demo.yml ps

# 触发一次全量同步
curl -X POST http://127.0.0.1:13607/api/sync/task/trigger \
     -H 'Content-Type: application/json' -d '{"mode":"db"}'

# 查看任务状态
curl http://127.0.0.1:13607/api/sync/task/status

# 验证目标库落库行数（测试数据预期 2301 条）
docker exec aifaizhi-pg-dst psql -U xcapp -d szsjzzkk \
  -c "SELECT count(*) FROM ods_dsjsj.ods_dsjsj_file_info;"

# 清理（含数据卷）
docker compose -f docker-compose.yml -f docker-compose.demo.yml down -v
```

> demo 栈通过 `deploy/config/application-demo.yml` 覆盖数据源地址为容器主机名（`aifaizhi-pg-src` / `aifaizhi-pg-dst`），该文件以 `application-local.yml` 挂载，配合 `SPRING_PROFILES_ACTIVE=local` 生效。

---

## 七、部署方式三：Kubernetes

清单位于 `deploy/k8s/`，使用 Kustomize 组织，统一部署到命名空间 `aifaizhi`。

### 7.1 设计要点

| 资源 | 说明 |
|---|---|
| `ConfigMap` | 挂载为容器内 `/app/config/application.yml`，覆盖镜像内置配置；数据库密码用 `${JM_CLOUD_PASSWORD}` / `${SZJZZKK_PASSWORD}` 占位 |
| `Secret` | 存放两个数据库密码，通过环境变量注入，解析 ConfigMap 中的占位符 |
| `Deployment` | 单副本（应用内已加锁，多副本会并发同步）；含 startup/liveness/readiness 三级探针；resources requests/limits；日志 `emptyDir` |
| `Service` | `ClusterIP:13607`，供集群内访问触发同步 |

探针设计：
- **startupProbe**：JVM 启动较慢，给足宽限期（最多 30×10s=5min），启动成功后才由 liveness 接管，避免误杀。
- **livenessProbe**：`/actuator/health/liveness`，只判活、不查 DB，DB 抖动不会导致 Pod 反复重启。
- **readinessProbe**：`/actuator/health/readiness`，未就绪时 Service 不转发流量。

### 7.2 部署步骤

```bash
# 1) 修改 deploy/k8s/configmap.yaml：填入数据库地址/账号（密码留占位符）
#    修改 deploy/k8s/secret.yaml：填入两个数据库的真实密码（stringData 明文，k8s 自动编码）
#    修改 deploy/k8s/deployment.yaml：把 image 改为私有仓库地址（或用 kustomization 的 images 字段）

# 2) 先渲染预览（不实际提交）
kubectl kustomize deploy/k8s

# 3) 应用到集群
kubectl apply -k deploy/k8s

# 4) 观察启动过程
kubectl -n aifaizhi get pods -w
kubectl -n aifaizhi logs -f deploy/aifaizhi-data-sync

# 5) 验证就绪
kubectl -n aifaizhi get svc aifaizhi-data-sync
kubectl -n aifaizhi port-forward svc/aifaizhi-data-sync 13607:13607 &
curl http://127.0.0.1:13607/actuator/health/readiness

# 触发同步
curl -X POST http://127.0.0.1:13607/api/sync/task/trigger \
     -H 'Content-Type: application/json' -d '{"mode":"db"}'
```

### 7.3 更新镜像地址（推荐）

不用改 `deployment.yaml`，在 `deploy/k8s/kustomization.yaml` 取消注释并修改：

```yaml
images:
  - name: aifaizhi-data-sync
    newName: registry.example.com/aifaizhi/aifaizhi-data-sync
    newTag: 0.0.1-SNAPSHOT
```

然后 `kubectl apply -k deploy/k8s` 即可滚动更新。

### 7.4 卸载

```bash
kubectl delete -k deploy/k8s
```

---

## 八、健康检查说明

| 端点 | 用途 | 是否查 DB | 典型耗时 |
|---|---|---|---|
| `GET /actuator/health/liveness` | 存活探针 | 否 | <10ms |
| `GET /actuator/health/readiness` | 就绪探针 | 否 | <10ms |
| `GET /actuator/health` | 手动排查（含 DB 连通性详情） | 是 | DB 不可达时可能阻塞数秒 |

> 为什么不用完整 `/actuator/health` 做探针？Spring Boot 的 DB 健康指示器会调用 `getConnection()`，
> 当数据库不可达时会阻塞到连接超时（默认 30s），导致探针超时。因此容器与 k8s 探针统一使用
> `liveness` / `readiness` 可用性组（只反映应用自身状态，不触碰外部依赖）；数据库异常通过
> 同步任务状态与日志体现，不会引发 Pod 无意义重启。

---

## 九、本次实测验证结果

以下为在本机（macOS + Docker 29.8 + Compose v5）实际执行的验证记录：

### 9.1 docker run

```
$ docker run -d --name aifsync-test -p 13608:13607 aifaizhi-data-sync:0.0.1-SNAPSHOT
$ docker ps --filter name=aifsync-test --format "{{.Status}}"
Up 22 seconds (healthy)                          ← 镜像内置 HEALTHCHECK 通过
$ curl http://127.0.0.1:13608/actuator/health/liveness
{"status":"UP"}  HTTP=200 t=0.008s              ← 探针快速返回
$ curl http://127.0.0.1:13608/api/sync/task/status
{"taskId":null,...,"running":false,...}          ← 业务接口可用
```

### 9.2 docker-compose demo 端到端

```
$ docker compose -f docker-compose.yml -f docker-compose.demo.yml ps
NAME                 STATUS
aifaizhi-data-sync   Up 37 seconds (healthy)
aifaizhi-pg-dst      Up 43 seconds (healthy)
aifaizhi-pg-src      Up 43 seconds (healthy)

$ curl -X POST http://127.0.0.1:13607/api/sync/task/trigger -d '{"mode":"db"}'
{"success":true,"message":"同步任务已启动",...}

$ curl http://127.0.0.1:13607/api/sync/task/status
{"running":false,"batchNo":3,"fetched":2301,"skipped":0,"success":2301,"failed":0,...}   ← 3 批全部成功

$ docker exec aifaizhi-pg-dst psql -U xcapp -d szsjzzkk -c "SELECT count(*) FROM ods_dsjsj.ods_dsjsj_file_info;"
  2301                                              ← 目标库落库正确

# 第二次触发，验证幂等：
{"fetched":2301,"skipped":2301,"success":0,"failed":0}   ← 全部跳过，无重复数据
```

### 9.3 k8s 清单（无集群，client 侧校验）

```
$ kubectl kustomize deploy/k8s        # 渲染成功，exit=0，无告警
$ kubectl apply --dry-run=client --validate=false -k deploy/k8s
namespace/aifaizhi created (dry run)
configmap/aifaizhi-data-sync-config created (dry run)
secret/aifaizhi-data-sync-secret created (dry run)
service/aifaizhi-data-sync created (dry run)
deployment.apps/aifaizhi-data-sync created (dry run)
```

渲染产物已确认包含：`startupProbe` / `livenessProbe` / `readinessProbe`、`containerPort: 13607`、
`/app/config/application.yml` 与 `/app/logs` 挂载、`JAVA_OPTS`（含 `MaxRAMPercentage`）等。

---

## 十、注意事项

1. **单实例部署**：同步服务应用内用锁保证同一时刻只有一个任务在跑，k8s `replicas` 请保持为 `1`，不要水平扩容，否则会并发触发同步。
2. **配置优先级**：外部 `/app/config/application.yml` > 镜像内置 `application.yml`。修改外部配置后需重启容器/k8s Pod 生效（ConfigMap 以 subPath 单文件挂载时不会热更新）。
3. **密码管理**：k8s `secret.yaml` 用 `stringData` 明文填写，方便编辑；生产环境建议改用 SealedSecret / Vault / 云厂商 KMS，且不要把真实密码提交到仓库。
4. **时区**：镜像与编排统一设置 `TZ=Asia/Shanghai`，JVM 也通过 `-Duser.timezone=Asia/Shanghai` 固定，避免容器默认 UTC 导致时间字段偏差。
5. **日志**：容器内日志输出到 `/app/logs`，已挂载到宿主机 `./logs`；k8s 中为 `emptyDir`，如需持久化请改用 PVC。
6. **数据库前置**：目标库 `szsjzzkk` 需先执行 `init/init-pg-sql.sql` 建表；demo 栈会自动执行，生产环境需手动建表（见 README）。
