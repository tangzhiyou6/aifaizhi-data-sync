# syntax=docker/dockerfile:1
# =============================================================================
# aifaizhi-data-sync 多阶段构建 Dockerfile
#   阶段一(builder)：用 Maven + JDK 8 打包（JDK 8 编译可规避 Lombok 1.18.x 在高版本 JDK 上的注解处理器问题）
#   阶段二(runtime)：仅含 JRE 8 + 产物，非 root 用户运行
# 构建：docker build -t aifaizhi-data-sync:0.0.1-SNAPSHOT .
# =============================================================================

# ------------------------------ 阶段一：构建 ------------------------------
FROM maven:3.9-eclipse-temurin-8 AS builder
WORKDIR /build

# 阿里云 Maven 镜像配置（加速国内依赖下载），仅构建期使用
COPY deploy/maven-settings.xml /tmp/settings.xml

# 先拷贝 pom，缓存依赖层（pom 不变时不会重复下载依赖）
# --mount=type=cache 复用宿主机的 Maven 本地仓库，跨构建不重复下载
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -s /tmp/settings.xml -DskipTests -q dependency:go-offline || true

# 拷贝源码并打包
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -s /tmp/settings.xml -DskipTests -q clean package \
 && ls -lh target/aifaizhi-data-sync.jar

# ------------------------------ 阶段二：运行 ------------------------------
FROM eclipse-temurin:8-jre AS runtime

# 安装 curl 供容器健康检查使用；清理 apt 缓存减小镜像体积
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates tzdata \
 && rm -rf /var/lib/apt/lists/*

# 时区与 JVM 默认参数（可被运行时 JAVA_OPTS 环境变量覆盖）
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai" \
    LANG=C.UTF-8

# 非 root 用户
RUN groupadd -r app && useradd -r -g app -d /app -s /sbin/nologin app \
 && mkdir -p /app/logs /app/config \
 && chown -R app:app /app

WORKDIR /app

COPY --from=builder --chown=app:app /build/target/aifaizhi-data-sync.jar /app/aifaizhi-data-sync.jar

USER app

EXPOSE 13607
VOLUME ["/app/logs"]

# 健康检查：用 liveness 探针（不触碰数据库，DB 不可达时也不会阻塞）
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://127.0.0.1:13607/actuator/health/liveness || exit 1

# 入口：转发 JAVA_OPTS 与命令行参数（如 --sync / --spring.profiles.active=local）
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -jar /app/aifaizhi-data-sync.jar \"$@\"", "aifaizhi-entrypoint"]
