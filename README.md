# aifaizhi-data-sync

SPIC 附件元数据增量同步服务：把源端库 `jm_cloud` 的 `spic_base.proj_biz_dm_stg_m` + `con_file_upload`
关联结果，同步到目标库 `szsjzzkk` 的 `ods_dsjsj.ods_dsjsj_file_info`。

- 技术栈：Spring Boot 2.7.18 + MyBatis-Plus 3.5.3.2 + dynamic-datasource 3.6.1 + PostgreSQL + Java 8
- 多数据源：`@DS("jm_cloud")` 读源库，`@DS("szsjzzkk")` 写目标库
- 批次：每批 1000 条；失败自动重试 3 次；失败记录落库，下次任务重投

## 一、数据流

```
源端 jm_cloud                                 目标端 szsjzzkk
┌────────────────────────────┐               ┌──────────────────────────────┐
│ spic_base.proj_biz_dm_stg_m │               │ ods_dsjsj.ods_dsjsj_file_info│ ← 业务落地表
│   relevant_id → file_id     │  ──────►      │ ods_dsjsj.ods_dsjsj_file_sync_record │ ← 同步记录表（幂等）
│ dws_dsjsj.con_file_upload   │               └──────────────────────────────┘
│   id → file_url, bucket     │
└────────────────────────────┘
```

SQL 等价于：

```sql
SELECT CAST(p.file_id AS VARCHAR)    AS file_id,
       MIN(CAST(p.relevant_id AS VARCHAR)) AS relevant_id,
       MIN(c.file_url)                AS file_url,
       MIN(c.bucket)                  AS bucket
FROM spic_base.proj_biz_dm_stg_m p
LEFT JOIN dws_dsjsj.con_file_upload c
       ON CAST(c.id AS VARCHAR) = CAST(p.file_id AS VARCHAR)
WHERE p.file_id IS NOT NULL AND CAST(p.file_id AS VARCHAR) <> ''
  AND CAST(p.relevant_id AS VARCHAR) = :relevantId      -- 按 relevant_id 过滤（可省略）
  AND CAST(p.file_id AS VARCHAR) > :cursor              -- 游标分页（可省略）
GROUP BY CAST(p.file_id AS VARCHAR)
ORDER BY file_id
LIMIT 1000;
```

- `GROUP BY file_id` 保证一个 file_id 只出一行，游标分页不会漏数据；
- `LEFT JOIN` 兼容 `con_file_upload` 里查不到的文件（`file_url`/`bucket` 为空）；
- `file_id` 为空的脏数据由 SQL 过滤掉。

## 二、两种方案

| 方案 | 模式值 | 说明 |
|---|---|---|
| 方案一：服务端分页 API + 客户端消费 | `api` | 服务端提供 `GET /api/sync/files` 分页接口，客户端按页拉取后写入目标库。适合源端与目标端分处不同网络/系统的情况 |
| 方案二：直连数据库同步 | `db` | 直接查源库（游标分页），批量写入目标库。同一进程内完成，效率更高 |

两种方案共用同一套「拉取 → 过滤已同步 → 批量 upsert → 记录结果」的流程，切换只需改 `data-sync.mode`。

### 方案一的服务端接口

```
GET /api/sync/files?relevantId=2086724333076062209&page=1&size=1000
```

返回：

```json
{
  "page": 1,
  "size": 1000,
  "hasNext": true,
  "records": [
    {
      "fileId": "2086724311626391553",
      "fileUrl": "https://oss-cn-beijing.aliyuncs.com/spic-bucket/2026/11/0001_附件.pdf",
      "bucket": "spic-bucket",
      "relevantId": "2086724333076062209"
    }
  ]
}
```

接口只读源端数据，不感知目标库；客户端拿到数据后再做幂等过滤和写入。

## 三、幂等与失败重投

| 机制 | 实现 |
|---|---|
| 不重复同步 | 同步记录表以 `record_key`（= `file_id`）唯一，`sync_status = 1` 视为已同步，下次任务直接跳过 |
| 失败可重投 | 同步失败时 `sync_status = 2` 并记录 `fail_reason`/`retry_count`，下次任务**不会**跳过，会重新同步 |
| 写入幂等 | 业务落地表以 `file_id` 唯一，同步走 `INSERT ... ON CONFLICT (file_id) DO UPDATE`，重复同步不会产生脏数据 |
| 失败不丢 | 业务落地表与同步记录表在**同一个事务**写入；失败状态只写同步记录表（业务表可能正因约束问题写不进去，写记录表才能保证失败可被记录） |
| 接口失败留痕 | 方案一接口访问最终失败时，写一条 `record_type = 2` 的页面级记录（`record_key = page:{relevantId}:{page}`） |

> 批次是原子的：某一批 1000 条里只要有一条写不进去，整批都会标记失败，下次整批重来（写入本身幂等，重来不会产生重复数据）。

## 四、目录结构

```
aifaizhi-data-sync
├── init/
│   ├── init-pg-sql.sql          # 目标库建表 DDL（ods_dsjsj_file_info + ods_dsjsj_file_sync_record）
│   └── init-src-test-data.sql   # 本地联调用源端测试数据
└── src/main/
    ├── java/com/hd/sync/
    │   ├── DataSyncApplication.java
    │   ├── client/SourceApiClient.java        # 方案一 API 客户端
    │   ├── common/RetryExecutor.java          # 统一重试（首次 + 3 次重试）
    │   ├── config/                             # 数据源/事务/线程池/同步参数
    │   ├── controller/                         # 源端分页接口 + 任务触发/状态
    │   ├── datasource/DataSourceNames.java
    │   ├── mapper/                             # 源端查询 + 目标端 upsert
    │   ├── model/                              # 源端 DTO / 目标端实体 / 接口返回体
    │   ├── service/                            # 查询、写入、同步核心
    │   └── task/                               # 任务管理、命令行/定时触发
    └── resources/
        ├── application.yml                     # 生产配置
        ├── application-local.yml               # 本地 Docker 联调配置
        └── logback-spring.xml
```

## 五、部署步骤

### 1. 建表（目标库 szsjzzkk）

```bash
psql -h 10.227.3.245 -U xcapp -d szsjzzkk -f init/init-pg-sql.sql
```

脚本会创建 `ods_dsjsj` schema、`ods_dsjsj_file_info`、`ods_dsjsj_file_sync_record` 及索引注释。
若 `xcapp` 不是 schema owner，需 DBA 执行脚本末尾的 `ALTER SCHEMA ... OWNER TO xcapp;`。

### 2. 配置

`src/main/resources/application.yml` 已按需求预置两个数据源：

```yaml
spring:
  datasource:
    dynamic:
      datasource:
        jm_cloud:
          driver-class-name: org.postgresql.Driver
          url: jdbc:postgresql://10.104.11.168:5432/jm_cloud?currentSchema=dws_dsjsj
          username: dsjsj
          password: jXY.S9P8QB
        szsjzzkk:
          driver-class-name: org.postgresql.Driver
          url: jdbc:postgresql://10.227.3.245:5432/szsjzzkk?currentSchema=xcapp
          username: xcapp
          password: D5c0eFsWXqj_
```

常用配置项：

| 配置 | 默认值 | 说明 |
|---|---|---|
| `data-sync.mode` | `db` | `db`=方案二直连同步，`api`=方案一消费分页接口 |
| `data-sync.batch-size` | `1000` | 每批条数 |
| `data-sync.max-retry-times` | `3` | 失败重试次数（首次 + 3 次重试 = 最多 4 次尝试） |
| `data-sync.retry-interval-ms` | `2000` | 重试间隔基数，第 n 次重试等待 `n × 该值` |
| `data-sync.relevant-id` | 空 | 为空同步全部 relevant_id |
| `data-sync.schedule-enabled` | `false` | 是否启用定时任务 |
| `data-sync.cron` | `0 0 3 * * ?` | 定时表达式 |
| `data-sync.auto-run-on-startup` | `false` | 启动后是否自动同步一次 |
| `data-sync.source.*` | - | 源端 schema/表名 |
| `data-sync.target.*` | - | 目标端 schema/表名 |
| `data-sync.api.base-url` | `http://127.0.0.1:13607` | 方案一客户端访问的源端接口地址 |

### 3. 打包与运行

```bash
mvn -DskipTests clean package
```

> **构建环境**：JDK 8 / 21 / 25 均可构建，产物固定为 Java 8 字节码（major version 52）。
> 项目根目录有 `.sdkmanrc`，sdkman 会在进入该目录时自动切换构建 JDK。
>
> 注意：Spring Boot 2.7.18 父 POM 默认管理的 Lombok 是 **1.18.30**，其注解处理器在 **JDK 22+** 上会静默失效，
> 编译表现为满屏「找不到符号：log / getter / setter」。本项目已把 Lombok 锁定为 1.18.48
> 并显式配置 `annotationProcessorPaths`，该问题不会再出现；若在其它项目遇到同样报错，
> 升级 Lombok 版本或改用 JDK 8 编译即可。

```bash
# 方式一：命令行跑一次就退出（适合 crontab）
java -jar target/aifaizhi-data-sync.jar --sync
java -jar target/aifaizhi-data-sync.jar --sync=2086724333076062209
java -jar target/aifaizhi-data-sync.jar --sync --mode=api

# 方式二：常驻服务 + REST 触发
java -jar target/aifaizhi-data-sync.jar
curl -X POST http://127.0.0.1:13607/api/sync/task/trigger \
     -H 'Content-Type: application/json' -d '{"mode":"db","relevantId":"2086724333076062209"}'
curl http://127.0.0.1:13607/api/sync/task/status

# 方式三：定时任务（application.yml 里 data-sync.schedule-enabled=true）
```

同一时刻只允许一个同步任务，重复触发会返回「上一次同步任务仍在执行」。

## 六、日志

- `logs/aifaizhi-data-sync.log`：应用日志
- `logs/data-sync.log`：同步过程专用日志（批次进度、成功/失败明细、重试记录）

关键日志格式：

```
[数据同步][db][第2批] 本批1000条, 已同步跳过1000条, 待同步0条
[数据同步][db][第2批] 写入成功1000条, 实际重试0次。累计：取数2000，成功1000，失败0，跳过1000
[数据同步] 第2批写入目标库 第1/4次尝试失败：ERROR: ...
[数据同步][db][第2批] 写入目标库失败，已重试3次，跳过本批数据并标记失败，下次任务会重新同步
```

## 七、本地验证

```bash
docker run -d --name aifsync-src -e POSTGRES_PASSWORD=srcpass -e POSTGRES_USER=dsjsj \
  -e POSTGRES_DB=jm_cloud -p 15432:5432 postgres:16
docker run -d --name aifsync-dst -e POSTGRES_PASSWORD=dstpass -e POSTGRES_USER=xcapp \
  -e POSTGRES_DB=szsjzzkk -p 15433:5432 postgres:16
docker cp init/init-src-test-data.sql aifsync-src:/tmp/
docker exec aifsync-src psql -U dsjsj -d jm_cloud -f /tmp/init-src-test-data.sql
docker cp init/init-pg-sql.sql aifsync-dst:/tmp/
docker exec aifsync-dst psql -U xcapp -d szsjzzkk -f /tmp/init-pg-sql.sql

java -jar target/aifaizhi-data-sync.jar --spring.profiles.active=local
```

测试数据：3000 个文件、2 个 relevant_id、2300 条关联关系 + 1 条 `file_id` 查不到文件的脏数据 + 1 条 `file_id` 为空的脏数据。
预期：分 3 批（1000 + 1000 + 301）同步 2301 条；再跑一次全部跳过。

## 八、常见问题

**Q：编译报「找不到符号：log / getTarget / setRecordKey」等，几十个方法全找不到？**

A：Lombok 注解处理器没运行。JDK 22+ 下 Lombok 1.18.30（Boot 2.7.x 默认版本）会静默失效。
处理：升级 Lombok 到 1.18.48（本项目已处理），或用 JDK 8 编译。

**Q：POM 报「Non-parseable POM ... in comment after two dashes」？**

A：XML 注释里写了 `--release` 这类含两个连字符的内容，`--` 在 XML 注释里非法，改成 `release` 即可。

---

## 九、注意事项

1. `spring.datasource.dynamic.aop.order` 保持为最高优先级（`-2147483648`），保证 `@DS` 的路由上下文先于事务建立，否则事务可能落在错误的库上。
2. `initialization-fail-timeout: -1` 表示数据库暂时不可达不会阻塞应用启动；如果希望启动即失败，改为默认的 `1`。
3. 方案一如果源端与本服务不在同一台机器，把 `data-sync.api.base-url` 指向源端服务地址，并在源端放行网络策略。
4. 全量同步（`relevant-id` 为空）时，一个 `file_id` 可能被多个 `relevant_id` 引用，落地表的 `relevant_id` 取其中最小值；若需要保留全部关联关系，需要把落地表主键改成 `(relevant_id, file_id)`。
