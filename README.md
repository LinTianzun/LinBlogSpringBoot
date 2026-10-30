# LinBlog · Spring Boot + Vue 3 重实现

个人博客系统（访客门户 + 管理后台一体化）的 **Vue 3 + Spring Boot 重实现**版本。
原实现基于 Express + MySQL，本仓库按《Vue3-SpringBoot重实现开发文档》逐模块重写后端与前端。

| 项 | 内容 |
| --- | --- |
| 后端 | Java 21 · Spring Boot 3.5.6 · MyBatis-Plus 3.5.12 · Flyway · Caffeine · jjwt · Bucket4j |
| 前端 | Vue 3.5 · TypeScript 5.9 · Vite 6 · Pinia 3 · Vue Router 4 · Naive UI · pnpm |
| 数据库 | MySQL 8.4（`utf8mb4` / `utf8mb4_0900_ai_ci`） |
| 部署 | Docker Compose（MySQL + 后端 + 前端 + Caddy 反代） |

> 后端约定：**禁用 Lombok**，全部使用 `record` 与显式代码。

## 目录结构

```
backend/                 Spring Boot 单体（Maven 单模块）
  src/main/java/com/linblog/
    common/              ApiResult / PageResult / 错误码 / 全局异常处理
    config/              安全、缓存、MyBatis-Plus、OpenAPI、启动强校验
    module/audit          请求审计、系统日志
    module/auth           登录、刷新令牌、密码重置
    module/permission     权限矩阵（角色 × 权限）
    module/user           用户与超管种子
    security/             JWT Cookie 过滤器、XSRF、限流、IP 工具
  src/main/resources/
    application.yml      默认配置（全部差异走环境变量）
    db/migration/        Flyway 迁移脚本
frontend/                Vue 3 + Vite 前端
  src/api/               request 封装与各域接口
  src/views/portal/      门户页面
  src/stores/            Pinia 状态
  src/styles/            全局样式（Less）
deploy/                  Docker Compose 部署栈与 Caddy 配置
Documentation/           蓝图、立案、开发进度、数据库与开发文档
.github/workflows/       CI 门禁（backend / frontend / contract / e2e）
```

## 本地开发

### 1. 数据库

准备一个 MySQL 8.4 实例（或直接用 `deploy/` 里的 compose 只起 mysql）：

```bash
docker compose -f deploy/docker-compose.yml up -d mysql
```

后端通过 Flyway 在启动时自动建表与迁移。

### 2. 后端

```bash
cd backend
cp .env.example .env        # 按需修改 DB_URL / DB_USERNAME / DB_PASSWORD / JWT_ACCESS_SECRET
mvn spring-boot:run
```

启动强校验（`com.linblog.config.AppStartupGuard`）要求以下变量齐备，缺失即拒绝启动：

- `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`
- `JWT_ACCESS_SECRET`（≥ 32 字符）
- `ALLOWED_ORIGINS`（逗号分隔的精确来源，如 `http://localhost:5173`）

完整变量清单见 `backend/.env.example`。接口文档在启动后由 springdoc 提供（`/v3/api-docs`）。

### 3. 前端

```bash
cd frontend
pnpm install
pnpm dev                    # http://localhost:5173，/api 已代理到 http://localhost:8080
```

常用脚本：`pnpm lint`、`pnpm test`（vitest）、`pnpm build`、`pnpm typecheck`、`pnpm sizecheck`。

## 生产部署

```bash
cd deploy
cp .env.example .env        # 填好 MYSQL_ROOT_PASSWORD / DB_* / JWT_ACCESS_SECRET / ALLOWED_ORIGINS
docker compose up -d --build
```

compose 文件需保持在 `<repo>/deploy/` 下使用（构建上下文是仓库根目录 `..`）。
Caddy 负责静态资源与 `/api` 反代，配置见 `deploy/Caddyfile`（本地 HTTP 调试用 `deploy/Caddyfile.local`）。

## 工程门禁（CI）

`.github/workflows/ci.yml` 在 push 到 `main` 与 PR 时运行四个 job：

| job | 内容 |
| --- | --- |
| backend | `mvn verify`（Testcontainers 真库）+ JaCoCo 行覆盖 ≥ 70%（`com/linblog`）+ 导出 `openapi.json` |
| frontend | `pnpm lint` / `vitest` / `build` / `size-limit` 体积预算 |
| contract | `openapi.json` → `pnpm gen:api`，`git diff --exit-code src/types/api.d.ts`，契约漂移即红 |
| e2e | `workflow_dispatch` 与每日定时：compose 全栈 + Playwright 冒烟 |

## 文档

| 文档 | 内容 |
| --- | --- |
| [重新设计蓝图](Documentation/重设计蓝图.md) | 架构与模块划分的总设计 |
| [项目立案](Documentation/项目立案.md) | 背景、目标、里程碑与验收标准 |
| [Vue3-SpringBoot 重实现开发文档](Documentation/Vue3-SpringBoot重实现开发文档.md) | 重实现的接口、约定与验收口径 |
| [开发进度](Documentation/开发进度.md) | 逐模块进度跟踪 |
| [项目搭建历程](Documentation/项目搭建历程.md) | 搭建过程与踩坑记录 |
| [Linlog 数据库文档](Documentation/Linlog%20数据库文档（Typora%20版）.md) | 表结构与字段说明 |

## 许可

暂未声明开源许可；如需开源请补充 `LICENSE`。
