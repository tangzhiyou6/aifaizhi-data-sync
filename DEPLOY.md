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
| `deploy/k8s/configmap.yaml` | 非敏感运行配置（挂载为 application.yml，含 Prometheus metrics 端点暴露 + tags） |
| `deploy/k8s/secret.yaml` | 数据库密码（Secret） |
| `deploy/k8s/deployment.yaml` | 工作负载（三级探针，readiness 为 **exec 业务探针**；SecurityContext 非 root；RollingUpdate `maxUnavailable=0`） |
| `deploy/k8s/service.yaml` | Service（ClusterIP:13607） |
| `deploy/k8s/pdb.yaml` | PodDisruptionBudget（`minAvailable=1`，防维护驱逐） |
| `deploy/k8s/servicemonitor.yaml` | Prometheus Operator ServiceMonitor（抓取 `/actuator/prometheus`） |
| `deploy/k8s/kustomization.yaml` | Kustomize 入口 |

> **运行时增强**：除了上一轮新增的 actuator 健康端点，本轮还引入了 `micrometer-registry-prometheus` 依赖（pom.xml），
> 使应用自动暴露 200+ 行 Prometheus 指标（JVM / HTTP / HikariCP 数据源），可直接被 ServiceMonitor 采集。
> k8s readinessProbe 升级为 exec 业务探针，真正检查业务接口而非仅 Spring 上下文。

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
| `Deployment` | 单副本（应用内已加锁，多副本会并发同步）；含 startup/liveness/readiness 三级探针（readiness 为业务级 exec 探针）；滚动更新 `maxUnavailable=0`；SecurityContext（非 root、禁提权）；resources requests/limits；日志 `emptyDir` |
| `Service` | `ClusterIP:13607`，供集群内访问触发同步；ServiceMonitor 从它的 `/actuator/prometheus` 采集指标 |
| `PodDisruptionBudget` | `minAvailable=1`，保护单实例服务不被 kubelet 驱逐/节点维护踢下线 |
| `ServiceMonitor` | Prometheus Operator 采集 `/actuator/prometheus`（需要集群已安装 Prometheus Operator） |

探针设计（三层防护）：

| 层级 | 类型 | 探针 | 检查对象 | 失败后果 |
|---|---|---|---|---|
| 启动 | `httpGet` | `startupProbe` | `/actuator/health/liveness` | 超时 Pod 被杀死重启 |
| 存活 | `httpGet` | `livenessProbe` | `/actuator/health/liveness`（不查 DB） | 超时 Pod 被杀死重启 |
| 就绪 | `exec` | `readinessProbe` | `/api/sync/task/status`（业务接口返回合法 JSON） | Service 自动摘掉流量，DB 恢复后自动上线 |

readiness 用 **exec 业务探针** 的原因：
- actuator readiness 只反映 Spring 上下文可用，**不检查业务通路**；DB 连接数耗尽、线程池拒绝、DataSource 被标记为 broken 时，应用进程仍返回 200，但业务接口挂了
- exec 探针脚本 `curl /api/sync/task/status | grep -qE '"running"|,"success"'` 真正走到业务层，且容器内置 curl，k8s exec 无需额外工具

### 7.2 部署步骤

```bash
# 1) 修改 deploy/k8s/configmap.yaml：填入数据库地址/账号（密码留占位符）
#    修改 deploy/k8s/secret.yaml：填入两个数据库的真实密码（stringData 明文，k8s 自动编码）
#    修改 deploy/k8s/deployment.yaml：把 image 改为私有仓库地址（或用 kustomization 的 images 字段）

# 2) 先渲染预览（不实际提交）
kubectl kustomize deploy/k8s

# 3) 应用到集群
kubectl apply -k deploy/k8s
```

> **ServiceMonitor 注意**：`servicemonitor.yaml` 需要集群已安装 Prometheus Operator CRD（`monitoring.coreos.com/v1`）。
> 如果 apply 时报 `no matches for kind "ServiceMonitor"`，注释掉 `kustomization.yaml` 中的 `- servicemonitor.yaml` 行，
> 或单独安装 CRD：`kubectl apply -f https://raw.githubusercontent.com/prometheus-operator/prometheus-operator/main/example/prometheus-operator-crd/monitoring.coreos.com_servicemonitors.yaml`。

```bash
# 4) 观察启动过程（Pod ready = readiness exec 探针通过 = 业务接口可达）
kubectl -n aifaizhi get pods -w
kubectl -n aifaizhi logs -f deploy/aifaizhi-data-sync

# 5) 验证就绪
kubectl -n aifaizhi get svc aifaizhi-data-sync
kubectl -n aifaizhi port-forward svc/aifaizhi-data-sync 13607:13607 &
curl http://127.0.0.1:13607/actuator/health/readiness
# 业务接口（readiness exec 探针实际检查的端点）
curl http://127.0.0.1:13607/api/sync/task/status

# 触发同步
curl -X POST http://127.0.0.1:13607/api/sync/task/trigger \
     -H 'Content-Type: application/json' -d '{"mode":"db"}'

# 查看 metrics（Prometheus 抓取的就是这个端点）
curl -s http://127.0.0.1:13607/actuator/prometheus | head -20
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

### 7.5 监控与告警

镜像内置了 `micrometer-registry-prometheus`，暴露 `/actuator/prometheus` 端点，ServiceMonitor 每 15s 抓取一次。Prometheus 可直接采集以下维度：

| 指标前缀 | 说明 | 典型告警条件 |
|---|---|---|
| `jvm_memory_used_bytes` | JVM 堆内存使用 | `heap_used / heap_max > 0.85` 持续 5min |
| `jvm_threads_live_threads` | 活动线程数 | 持续 > 500（本服务一般 50-100） |
| `http_server_requests_seconds` | HTTP 请求耗时 | 99 分位 > 5s 持续 10min |
| `jdbc_connections_active` | HikariCP 活动连接数 | 持续 == `jdbc_connections_max`（连接池耗尽） |
| `jdbc_connections_pending` | 等待连接的请求数 | 持续 > 0（有请求在等连接） |
| `hikaricp_connections_timeout_total` | 获取连接超时次数 | 10min 内增加 > 0 |

Grafana 看板建议：
- JVM 内存 + GC 暂停面板
- HTTP 请求速率 / P99 耗时面板（按 URI 分组）
- 两个数据源的 HikariCP 连接池状态面板

---

## 八、健康检查说明

| 端点 | 用途 | 探针类型 | 是否查 DB | 典型耗时 |
|---|---|---|---|---|
| `GET /actuator/health/liveness` | 存活（启动/存活探针） | `httpGet` | 否 | <10ms |
| `GET /actuator/health/readiness` | 就绪手动排查 | — | 否 | <10ms |
| `GET /api/sync/task/status` | 业务就绪探针 | `exec`（k8s） | 间接触发 | 通常 <50ms，DB 不可达时 timeoutSeconds=8s 失败 |
| `GET /actuator/health` | 完整健康（手动排查） | — | 是（会 getConnection） | DB 不可达时阻塞到连接超时 |
| `GET /actuator/prometheus` | Prometheus 指标采集 | ServiceMonitor | 否 | <50ms |

> **为什么 k8s readiness 用 exec 业务探针而不是 httpGet /actuator/health？** 三个原因：
> 1. **DB 阻塞问题**：完整 `/actuator/health` 的 DB 健康指示器会 `getConnection()`，DB 不可达时阻塞到 HikariCP 超时（30s），探针超时导致 Pod 被反复重启
> 2. **actuator readiness 不够深**：它只检查 Spring 上下文 + Application readiness state，不检查业务通路；DataSource 的 `broken` 状态、HikariCP 的连接耗尽都不会让它返回 DOWN
> 3. **exec 业务探针**调的是 `/api/sync/task/status`，真正走到业务层查询同步状态，DB 有问题时会在 8s 内自然超时失败，Service 自动摘掉该 Pod 的流量

三个探针配合 PDB，形成**三层防护**：
1. Pod 挂了 → liveness 杀 Pod → Deployment 拉起新 Pod
2. 业务通路坏了但进程活着 → readiness 失败 → Service 摘掉流量 → DB 恢复后自动上线
3. 节点要维护 → PDB `minAvailable=1` 阻止驱逐 → 等维护完成或手动 drain 时确认服务在线

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
$ kubectl kustomize deploy/k8s > /tmp/k8s.yaml
# 7 个资源全部正确渲染：namespace/configmap/secret/deployment/service/pdb/servicemonitor

$ kubectl apply --dry-run=client --validate=false -k deploy/k8s
namespace/aifaizhi created (dry run)
configmap/aifaizhi-data-sync-config created (dry run)
secret/aifaizhi-data-sync-secret created (dry run)
service/aifaizhi-data-sync created (dry run)
deployment.apps/aifaizhi-data-sync created (dry run)
poddisruptionbudget.policy/aifaizhi-data-sync created (dry run)
# ServiceMonitor 报 "no matches for kind" 是预期行为——本环境未安装 Prometheus Operator CRD；
# 生产环境装了就不会报，或注释掉 kustomization.yaml 中的 servicemonitor.yaml 行

$ grep -c "^kind:" /tmp/k8s.yaml
7

# 关键探针/安全/滚动字段渲染确认
grep "exec:" /tmp/k8s.yaml                    → readinessProbe 的 exec 存在
grep "grep -qE" /tmp/k8s.yaml                 → 业务探针脚本存在
grep "runAsNonRoot: true" /tmp/k8s.yaml       → SecurityContext 生效
grep "minAvailable: 1" /tmp/k8s.yaml          → PDB 生效
grep "maxUnavailable: 0" /tmp/k8s.yaml        → RollingUpdate 生效
grep "path: /actuator/prometheus" /tmp/k8s.yaml → ServiceMonitor 生效
```

### 9.4 Prometheus metrics 端点验证（新镜像）

```
$ docker run -d --name test -p 13610:13607 aifaizhi-data-sync:0.0.1-SNAPSHOT
$ curl -s http://127.0.0.1:13610/actuator/prometheus | wc -l
229                                          ← 229 行 Prometheus 格式指标
$ curl -s http://127.0.0.1:13610/actuator/prometheus | head -15
# HELP executor_completed_tasks_total ...
executor_completed_tasks_total{app="aifaizhi-data-sync",name="taskScheduler",} 0.0
# HELP jdbc_connections_max ...
jdbc_connections_max{app="aifaizhi-data-sync",name="dataSource",} 10.0    ← 数据源指标带 app tag
...
$ docker rm -f test
```

自动带上了 `app="aifaizhi-data-sync"` tag（来自 ConfigMap 的 `management.metrics.tags`），Prometheus 侧按 `{app="aifaizhi-data-sync"}` 即可聚合所有指标。

### 9.5 exec 业务探针脚本模拟

```
$ body=$(curl -fsS -m 5 http://127.0.0.1:13607/api/sync/task/status)
$ echo "$body" | grep -qE '"running"|,"success"' && echo OK
OK                                           ← k8s readinessProbe 的 exec 脚本逻辑通过
```

---

## 十、注意事项

1. **单实例部署**：同步服务应用内用锁保证同一时刻只有一个任务在跑，k8s `replicas` 请保持为 `1`，不要水平扩容，否则会并发触发同步。
2. **配置优先级**：外部 `/app/config/application.yml` > 镜像内置 `application.yml`。修改外部配置后需重启容器/k8s Pod 生效（ConfigMap 以 subPath 单文件挂载时不会热更新）。
3. **密码管理**：k8s `secret.yaml` 用 `stringData` 明文填写，方便编辑；生产环境建议改用 SealedSecret / Vault / 云厂商 KMS，且不要把真实密码提交到仓库。
4. **时区**：镜像与编排统一设置 `TZ=Asia/Shanghai`，JVM 也通过 `-Duser.timezone=Asia/Shanghai` 固定，避免容器默认 UTC 导致时间字段偏差。
5. **日志**：容器内日志输出到 `/app/logs`，已挂载到宿主机 `./logs`；k8s 中为 `emptyDir`，如需持久化请改用 PVC。
6. **数据库前置**：目标库 `szsjzzkk` 需先执行 `init/init-pg-sql.sql` 建表；demo 栈会自动执行，生产环境需手动建表（见 README）。
