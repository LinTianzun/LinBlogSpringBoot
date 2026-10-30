# LinBlog 后端镜像（文档 §2 / §10 里程碑 0）
# 构建上下文必须是仓库根目录（compose 已设置 context: ..），即：
#   docker build -f deploy/backend/Dockerfile .
# 所有运行参数（SERVER_PORT、DB_URL、JWT_ACCESS_SECRET 等）一律由 compose 注入，
# 镜像内不写死任何业务配置。

# ── 构建阶段：Maven 3.9 + JDK 21 ─────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只拷 pom 下载依赖，充分利用层缓存（源码变动不重拉依赖）
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline

COPY backend/src ./src
# 最终 jar 名取决于 pom 的 artifactId-version，这里统一改名为 app.jar
RUN mvn -q -B -DskipTests package \
 && cp target/*.jar /app.jar

# ── 运行阶段：JRE 21 + 非 root ───────────────────────────────────────────
FROM eclipse-temurin:21-jre AS runtime

# 健康检查依赖 curl（temurin 基础镜像不带 wget/curl）
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 1001 --user-group --home-dir /app --shell /usr/sbin/nologin spring

WORKDIR /app
COPY --from=build /app.jar ./app.jar

# 本地文件存储目录：STORAGE_LOCAL_DIR=./data/storage 相对 WORKDIR /app
# 解析为 /app/data/storage（compose 用同名挂载点挂 named volume storage_data）
RUN mkdir -p /app/data/storage && chown -R spring:spring /app

USER spring
EXPOSE 8080

# SERVER_PORT 由 compose 注入；shell 形式使其在容器启动时才展开
HEALTHCHECK --interval=15s --timeout=5s --start-period=120s --retries=5 \
  CMD curl -fsS "http://127.0.0.1:${SERVER_PORT:-8080}/actuator/health" || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
