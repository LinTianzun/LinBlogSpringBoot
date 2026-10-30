# LinBlog · Vue3 + Spring Boot 重实现开发文档

| 文档信息 | 内容 |
| --- | --- |
| 文档性质 | 可执行开发文档：按本文即可从零搭建，是《重设计蓝图》在 Java 技术栈上的具体化 |
| 撰写日期 | 2026-10-30 |
| 蓝图继承 | 五条第一天默认值全部保留；唯一偏差见 §2.1「类型单源」——蓝图的全栈 TS/zod 在 Java 栈下等价改为 **OpenAPI 契约生成** |
| 部署假设 | 单人开发、单进程 + 单库 + 反向代理、2C4G；不做微服务/消息队列/多租户 |
| 配套文档 | 重设计蓝图.md（为什么这样设计）、项目立案.md（现行项目计划）、开发进度.md（现行项目现状） |

---

## 1. 总体架构与技术选型

```
浏览器 (Vue3 SPA)
   │  Cookie(JWT access, httpOnly) + XSRF-Token(double submit)
   ▼
Caddy 反向代理 (自动 HTTPS, SSE 透传)
   ▼
Spring Boot 单体 (8080)
   ├─ Spring Security 过滤链（认证 + 权限 Authority）
   ├─ REST 控制器层 (springdoc 生成 OpenAPI)
   ├─ Service 层（业务 + 事务）
   ├─ MyBatis-Plus（SQL 构建器 + 分页）
   ├─ Flyway（版本化迁移）        ├─ Caffeine（权限/配置缓存）
   ├─ SseEmitter 注册表（实时推送）  ├─ Thumbnailator + webp-imageio（图片管线）
   └─ StorageDriver 接口 → LocalDriver / S3Driver(MinIO)
   ▼
MySQL 8.4 (数据)   磁盘/MinIO (图片与文件)   SMTP (邮件)
```

### 1.1 选型清单（用到的技术）

| 层 | 技术 | 版本基线 | 用途 / 选型理由 |
| --- | --- | --- | --- |
| 语言 | Java | 21 LTS（25 LTS 可选） | 虚拟线程开箱即用，SSE 长连接不占平台线程 |
| 后端框架 | Spring Boot | 3.5.x（以撰写日最新稳定 3.x 为准） | Web/Security/Validation/Actuator 一体 |
| 数据访问 | MyBatis-Plus | 3.5.x | 保留「显式 SQL + 构建器」习惯（对齐原项目模型层），不用 JPA 隐式关系 |
| 迁移 | Flyway | 随 Boot | 版本化 DDL，替代自研 `schema_migrations` 记账 |
| 认证 | Spring Security + jjwt | 0.12.x | 无状态 JWT access + DB 轮换 refresh |
| 缓存 | Caffeine | 3.x | 权限矩阵进程内缓存，保存即失效 |
| 图片处理 | Thumbnailator + webp-imageio | — | 缩略图 + WebP 编码（替代 sharp） |
| 文件类型探测 | Apache Tika core | 2.x | 按 magic bytes 校验，不信任扩展名 |
| 对象存储 | MinIO（compose 内置）+ AWS SDK v2 | — | S3 协议统一本地/云端两 driver |
| API 文档 | springdoc-openapi | 2.x | 运行时生成 `openapi.json`，前端类型由它生成 |
| 前端框架 | Vue | 3.5+ | Composition API + `<script setup>` |
| 构建 | Vite | 6+ | 与原前端一致，迁移成本最低 |
| 状态/路由 | Pinia / Vue Router | 最新 | 与原前端一致 |
| 富文本 | Tiptap v2 | 2.x | 存 ProseMirror JSON，按需扩展，chunk 可控 |
| UI 组件 | Naive UI | 2.x | Vue3 原生、tree-shakable；替代 @yike-design/ui |
| 图标 | unplugin-icons | — | 按需编译，替代整包注册（原项目为此手工逐个注册） |
| 图表 | ECharts | 5.x | 仅概览页动态 import |
| HTTP | 原生 fetch 封装 | — | 统一错误壳/刷新重试/XSRF 头 |
| 测试(后) | JUnit5 + Mockito + Testcontainers + MockMvc | — | 仓库层跑真实 MySQL |
| 测试(前) | Vitest + @vue/test-utils + Playwright | — | 单测/组件/E2E |
| 质量 | Checkstyle(可选) + JaCoCo + size-limit | — | CI 门禁 |
| 数据库 | MySQL | 8.4 LTS | utf8mb4、ngram 全文检索 |

### 1.2 「类型只有一份」在 Java 栈下的实现

后端控制器注解 `@Validated` + springdoc 注解生成 OpenAPI 3 契约；CI 把 `openapi.json` 作为构建产物，前端执行 `pnpm gen:api`（openapi-typescript）生成 `src/types/api.d.ts`。**契约漂移 = CI 失败**（后端改了响应、前端类型自动跟进，不再手抄 interface——对应原项目 `lin_blog/src/api/*.ts` 手工镜像的痛点）。

## 2. 工程结构

```
linblog/
├─ backend/                        # Maven 单模块
│  └─ src/main/java/com/linblog/
│     ├─ common/                   # 响应壳、异常、错误码、分页、工具
│     ├─ config/                   # Security、OpenAPI、缓存、存储、调度、属性类
│     ├─ security/                 # JWT 过滤器、UserDetailsService、刷新轮换、XSRF
│     ├─ module/
│     │  ├─ auth/      (注册/登录/刷新/重置/改密)
│     │  ├─ user/      (用户管理)
│     │  ├─ permission/(目录/矩阵/缓存)
│     │  ├─ content/   (article+image 统一内容、标签、合集)
│     │  ├─ diary/     (日记)
│     │  ├─ comment/   (统一评论、点赞、举报)
│     │  ├─ message/   (私信会话)
│     │  ├─ guestbook/ (留言墙)
│     │  ├─ media/     (上传、storage driver、图片管线)
│     │  ├─ stats/     (浏览统计、概览)
│     │  ├─ audit/     (请求审计、系统日志)
│     │  └─ realtime/  (SSE 注册表与事件)
│     └─ resources/db/migration/   # V1__init.sql, V2__...
├─ frontend/
│  ├─ src/
│  │  ├─ api/          # 生成的类型 + 各域请求函数
│  │  ├─ router/       # 路由表 + 守卫
│  │  ├─ stores/       # session.ts(会话/权限) 等
│  │  ├─ components/   # 门户/后台/编辑器/评论
│  │  ├─ views/portal/ # 门户页
│  │  ├─ views/admin/  # 后台页
│  │  └─ utils/        # sse.ts、format.ts、renderContent.ts(Tiptap JSON→VNode)
│  └─ e2e/             # Playwright
├─ deploy/             # docker-compose.yml、Caddyfile、backup.sh
└─ .github/workflows/  # ci.yml（后端/前端双 job + 契约检查 + size-limit）
```

## 3. 数据库设计

约定：主键 `id BIGINT UNSIGNED AUTO_INCREMENT`；时间统一 `DATETIME(3)`；字符集 `utf8mb4_0900_ai_ci`；软删用状态列（不用 deleted_at，与原项目口径一致）；所有外键只建索引不建物理外键（便于归档与迁移）。以下「索引」行只列非主键索引。

### 3.1 用户与认证

**users**（用户）
| 字段 | 类型 | 约束/说明 |
| --- | --- | --- |
| id | BIGINT UNSIGNED | PK AI |
| username | VARCHAR(50) | UNIQUE，`^[a-zA-Z0-9_]{3,20}$` |
| email | VARCHAR(100) | UNIQUE |
| password_hash | VARCHAR(100) | bcrypt cost 12 |
| nickname | VARCHAR(50) | 展示名 |
| avatar_media_id | BIGINT UNSIGNED | NULL，关联 media |
| role_code | VARCHAR(20) | super_admin / admin / editor / viewer（默认 viewer） |
| is_super_admin | TINYINT(1) | 生成列：`role_code='super_admin'` |
| status | TINYINT(1) | 1 正常 / 0 禁用 |
| token_version | INT | 默认 0；改密/重置 +1，旧 access 全失效 |
| created_at / updated_at | DATETIME(3) | |

索引：`uk_username(username)`、`uk_email(email)`、`uk_single_super_admin((CASE WHEN is_super_admin=1 THEN 1 END))`（MySQL 8 函数索引，非超管为 NULL，唯一约束允许多 NULL → 数据库层保证全库唯一超管）。

**refresh_tokens**（刷新令牌，轮换 + 复用检测）
| 字段 | 类型 | 约束/说明 |
| --- | --- | --- |
| id | BIGINT UNSIGNED | PK AI |
| user_id | BIGINT UNSIGNED | idx |
| family_id | CHAR(36) | UUID；同一登录会话一族 |
| token_hash | CHAR(64) | SHA-256，UNIQUE；明文只在 Cookie |
| expires_at | DATETIME(3) | 默认 7 天 |
| revoked_at | DATETIME(3) | NULL=有效 |
| rotated_to_id | BIGINT UNSIGNED | 轮换指向的新 token |
| created_ip | VARCHAR(45) / created_ua_hash CHAR(32) | 审计 |

索引：`uk_token_hash`、`idx_user(user_id, expires_at)`、`idx_family(family_id)`。
**复用检测**：刷新时发现 token 已 revoked → 整个 family 立即吊销并 `token_version+1`，写审计。

**password_reset_tokens**：id, user_id, token_hash CHAR(64) UNIQUE, expires_at（30 分钟）, used_at NULL, created_at。一次性，用后即写 used_at。

**role_permissions**（权限矩阵持久层；默认矩阵在代码常量，此表存覆盖值）
| 字段 | 类型 | 说明 |
| --- | --- | --- |
| role_code | VARCHAR(20) | 联合 PK |
| perm_key | VARCHAR(40) | 联合 PK，如 `article.create` |

### 3.2 内容域

**content**（文章 + 图库统一表，蓝图正式化原 `article.classify`）
| 字段 | 类型 | 约束/说明 |
| --- | --- | --- |
| id | BIGINT UNSIGNED | PK AI |
| author_id | BIGINT UNSIGNED | idx |
| content_type | VARCHAR(10) | `article` / `image` |
| title | VARCHAR(150) | article 必填；image 类型存图注标题 |
| introduce | VARCHAR(512) | 摘要 |
| body | MEDIUMTEXT | **Tiptap JSON**（image 类型为 NULL，图片走 media 关联） |
| plain_text | MEDIUMTEXT | 服务端从 JSON 提取的纯文本，仅供检索 |
| cover_media_id | BIGINT UNSIGNED | NULL |
| state | VARCHAR(10) | `draft` / `published` / `hidden` / `deleted`，默认 draft |
| published_at | DATETIME(3) | NULL，发布时写入 |
| views / praises / comments_count | INT UNSIGNED | 冗余计数（明细表为准，事务内 `GREATEST(x-1,0)` 回退） |
| is_pinned | TINYINT(1) | 门顶置 |
| created_at / updated_at | DATETIME(3) | |

索引：`idx_list(content_type, state, published_at DESC)`、`idx_author(author_id, state)`、`FULLTEXT ft_search(title, plain_text) WITH PARSER ngram`（中文检索）。

**media**（图片/文件元数据，二进制一律在盘上/对象存储——蓝图默认值 1）
| 字段 | 类型 | 约束/说明 |
| --- | --- | --- |
| id | BIGINT UNSIGNED | PK AI |
| storage_key | VARCHAR(200) | UNIQUE，如 `2026/10/ab12….webp` |
| thumb_key | VARCHAR(200) | NULL，缩略图 key（生成后落盘缓存，不做内存 LRU） |
| kind | VARCHAR(10) | `image` / `file` |
| sha256 | CHAR(64) | idx，内容去重（同 hash 复用 storage_key） |
| original_name / mime_type | VARCHAR(255) / VARCHAR(100) | 白名单校验后入库 |
| width / height | INT | NULL |
| size_bytes | BIGINT | |
| content_id | BIGINT UNSIGNED | NULL，作为图库项/封面时的归属 |
| uploader_id | BIGINT UNSIGNED | |
| state | VARCHAR(10) | `active` / `trash` / `purged` |
| trashed_at | DATETIME(3) | NULL |
| created_at | DATETIME(3) | |

索引：`uk_storage_key`、`idx_content(content_id)`、`idx_state_trashed(state, trashed_at)`（清理任务扫描）。

**labels**：id, name VARCHAR(30) UNIQUE。
**content_labels**：content_id + label_id 联合 PK；`idx_label(label_id)`（替代原 `FIND_IN_SET`，筛选走索引）。
**subsets**（合集）：id, name VARCHAR(50), description VARCHAR(200), cover_media_id, owner_id, created_at。
**subset_items**：subset_id + content_id 联合 PK，sort INT。

**diaries**（日记，独立域不并入 content）
| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id / author_id | BIGINT UNSIGNED | |
| diary_date | DATE | UNIQUE(author_id, diary_date) 一天一篇 |
| weather | VARCHAR(10) | 白名单 8 值：晴天/多云/大雪/大雨/阵风/雷雨/雾霾/夜晚 |
| mood | VARCHAR(20) | 自由短语 |
| body | MEDIUMTEXT | Tiptap JSON |
| state | VARCHAR(10) | 同 content |
| views / praises | INT UNSIGNED | |
| created_at / updated_at | DATETIME(3) | |

**interactions**（内容/日记点赞，统一表）
id, target_type ENUM(`content`,`diary`), target_id, user_id, created_at；`uk_target_user(target_type, target_id, user_id)`。

### 3.3 评论域（三端统一：文章/日记/留言墙回复）

**comments**
| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT UNSIGNED | PK AI |
| target_type | VARCHAR(10) | `content` / `diary` / `guestbook` |
| target_id | BIGINT UNSIGNED | 对应 content.id / diaries.id / guestbook.id |
| author_id | BIGINT UNSIGNED | |
| parent_id | BIGINT UNSIGNED | NULL=一级；非 NULL=所属一级评论 id（两级封顶） |
| reply_to_comment_id | BIGINT UNSIGNED | NULL；楼中楼指向的被回复评论 |
| reply_to_user_id | BIGINT UNSIGNED | NULL；冗余被回复人，避免多跳查询 |
| body | VARCHAR(2000) | 纯文本评论（不走富文本） |
| praise_count | INT UNSIGNED | 冗余 |
| status | VARCHAR(10) | `visible` / `deleted`（逻辑删） |
| created_at | DATETIME(3) | |

索引：`idx_target(target_type, target_id, status, parent_id, created_at)`、`idx_author(author_id)`。

**comment_praises**：comment_id + user_id 联合 PK。
**comment_reports**（举报）
| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id / comment_id / reporter_id | BIGINT UNSIGNED | `uk_comment_reporter(comment_id, reporter_id)` |
| reason | VARCHAR(20) | 白名单 spam/abuse/porn/illegal/misinformation/other |
| detail | VARCHAR(200) | NULL 补充说明 |
| status | VARCHAR(10) | `pending` / `resolved` / `dismissed` |
| handled_by / handled_at | BIGINT UNSIGNED / DATETIME(3) | NULL |
| handle_note | VARCHAR(200) | NULL |
| created_at | DATETIME(3) | |

索引：`idx_status(status, created_at)`（工作台队列）。

### 3.4 私信与留言墙

**conversations**：id, user_a_id, user_b_id（约定 a<b，`uk_pair(user_a_id, user_b_id)`）, last_message_at, a_unread, b_unread, created_at。
**direct_messages**：id, conversation_id (idx), sender_id, body VARCHAR(2000), read_at NULL, created_at；`idx_conv_time(conversation_id, created_at DESC)`（游标分页）。
**guestbook**（留言墙一级留言）：id, user_id, body VARCHAR(2000), status `visible/deleted`, created_at；回复走 `comments(target_type='guestbook', target_id=guestbook.id)`。

### 3.5 统计与审计

**view_logs**（业务浏览统计）：id, target_type ENUM(`content`,`diary`,`portal`), target_id BIGINT NULL, user_id NULL, visitor_key CHAR(64)（登录 `u:{id}` 的 SHA-256；游客 SHA-256(IP+UA)，**不落明文 IP**）, viewed_at DATETIME(3)。
索引：`idx_target_time(target_type, target_id, viewed_at)`、`idx_time(viewed_at)`（7 天保留清理）。去重窗口 30 分钟，见 §8.6。

**request_audits**（请求审计，原 record 表）：id, request_id CHAR(32), user_id NULL, method VARCHAR(10), path VARCHAR(200), status INT, ip VARCHAR(45), ua VARCHAR(300), duration_ms INT, geo_country VARCHAR(60) NULL, geo_city VARCHAR(60) NULL, created_at。`idx_time(created_at)`（90 天滚动清理）、`idx_user(user_id)`。geo 默认关闭（`GEOIP_ENABLED=false`）。

**system_logs**（业务操作日志）：id, user_id, module VARCHAR(30), action VARCHAR(30), target_type VARCHAR(30), target_id BIGINT, detail VARCHAR(500), ip VARCHAR(45), created_at。`idx_module_time(module, created_at)`、`idx_user(user_id)`。

**files**（后台文件管理域，独立于 media 的通用附件）：
id, uploader_id, storage_key UNIQUE, original_name, mime_type, size_bytes, sha256 CHAR(64), state `active/trash/purged`, trashed_at NULL, created_at。配额与备份沿用原项目数值：单文件 ≤20MB、备份区上限 2GiB（80% 告警）、trash 保留 30 天。

## 4. 参数与约定设计

### 4.1 响应壳与错误码

```json
{ "code": 0, "message": "ok", "data": { }, "requestId": "8f3a…" }
```

| code | HTTP | 含义 | 前端行为 |
| --- | --- | --- | --- |
| 0 | 200 | 成功 | 取 data |
| 40000 | 400 | 通用参数错误 | 表单提示 |
| 40001 | 400 | 字段校验失败（data.fieldErrors 数组） | 逐字段标红 |
| 40100 | 401 | 未认证 | 跳登录 |
| 40101 | 401 | access 过期 | 静默调 refresh 后重放原请求（单飞） |
| 40102 | 401 | refresh 无效/重放 | 清会话，跳登录 |
| 40300 | 403 | 无权限（仅管理端返回） | 提示无权限 |
| 40400 | 404 | 不存在**或**无权访问（内容读取类统一 404，防探测） | 404 页 |
| 40900 | 409 | 状态冲突（重复举报、重复超管等） | 提示 |
| 42900 | 429 | 频率限制（带 Retry-After 头） | 倒计时 |
| 50000 | 500 | 内部错误 | 提示 + requestId 反馈 |

### 4.2 通用请求参数

| 参数 | 约束 | 说明 |
| --- | --- | --- |
| page | ≥1，默认 1 | 页码 |
| pageSize | 1–50，默认 10 | 每页 |
| keyword | ≤100 字符 | 检索词（服务端 trim） |
| sort | 枚举 | 列表类 `latest`(默认)/`hot`（按 published_at / views+praise 加权） |
| targetType | 枚举 | 互动/评论类 `article`/`image`/`diary`/`guestbook`（服务端白名单映射到表） |

**Cookie 设计**（蓝图默认值 2）：
- `linlog_at`：JWT access，HttpOnly + SameSite=Lax + Path=/，15 分钟；
- `linlog_rt`：refresh 原文，HttpOnly + SameSite=Strict + **Path=/api/v1/auth**（只有刷新端点会带）；
- `XSRF-TOKEN`：非 HttpOnly，双提交；所有非 GET 请求须带 `X-XSRF-TOKEN` 头，另校验 `Origin` ∈ ALLOWED_ORIGINS。
- **上传票据机制不存在**：cookie 对 XHR/FormData 自动生效，原项目 `authMiddlewareFromQuery` + 票据池整块消失。

### 4.3 环境参数（application.yml + 环境变量）

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| SERVER_PORT | 8080 | |
| DB_URL / DB_USERNAME / DB_PASSWORD | — | 启动强校验，缺失拒绝启动（继承原 config/default.js 哲学）；应用库名固定为 `springlinblog` |
| JWT_ACCESS_SECRET | 必填 ≥32 字符 | |
| JWT_ACCESS_TTL / REFRESH_TTL_DAYS | PT15M / 7 | |
| COOKIE_DOMAIN / COOKIE_SECURE | 空 / true(prod) | 跨子域部署时设 DOMAIN |
| ALLOWED_ORIGINS | 必填 | 逗号分隔精确来源，new URL 校验 |
| PUBLIC_URL | 回退 origins[0] | 生成 sitemap/邮件链接 |
| STORAGE_DRIVER | local | `local` / `s3` |
| STORAGE_LOCAL_DIR | ./data/storage | |
| S3_ENDPOINT / S3_BUCKET / S3_AK / S3_SK | — | driver=s3 时必填 |
| THUMB_WIDTHS | 320,640,1280 | 缩略图落盘 |
| UPLOAD_MAX_BYTES | 20MB | 文件 |
| VIEW_DEDUP_MINUTES | 30 | 浏览去重窗口 |
| AUDIT_RETENTION_DAYS / TRASH_RETENTION_DAYS | 90 / 30 | 定时清理 |
| GEOIP_ENABLED | false | |
| SMTP_HOST/PORT/SECURE/USER/PASS/FROM | — | 配 HOST 则 FROM 必填 |
| RATE_LIMIT_LOGIN / RESET | 5/min IP | Bucket4j |

## 5. 后端路由设计（API 清单）

前缀 `/api/v1`。权限列写判定键；`登录`=任意认证用户；`公开`=匿名可访问。除标注外，写操作均要求 XSRF 头。

### 5.1 认证 auth
| 方法 路径 | 权限 | 参数 / 返回要点 |
| --- | --- | --- |
| POST /auth/register | 公开(限流) | {username,email,password,nickname?}；并发重名 40900 |
| POST /auth/login | 公开(限流) | {account,password}；成功 Set-Cookie 两个令牌；禁用号 40300「账号已禁用」 |
| POST /auth/refresh | rt Cookie | 轮换：旧 token revoked→新对；检测重放→吊销全 family + 40102 |
| POST /auth/logout | 登录 | 吊销 family，清 Cookie |
| GET /auth/me | 登录 | {user, permissions[], isSuperAdmin} |
| POST /auth/password/reset-request | 公开(限流) | {email}；恒返回 ok（不暴露邮箱存在性），邮件含 30 分钟链接 |
| POST /auth/password/reset | 公开(限流) | {token,newPassword}；token 一次性，成功 token_version+1 |
| POST /auth/password/change | 登录 | {oldPassword,newPassword}；token_version+1 → 全端下线 |

### 5.2 权限 permission
| 方法 路径 | 权限 | 要点 |
| --- | --- | --- |
| GET /permissions/catalog | permission.view | 权限分组目录 + 受保护标记 |
| GET /permissions/me | 登录 | 当前角色可用键（登录后首拉，缓存 Pinia） |
| PUT /permissions/matrix | 仅超管 | {matrix:{roleCode: keys[]}}；拒绝修改受保护键（permission.edit / user.assignAdmin）；保存后失效 Caffeine 缓存 + 写系统日志 |

**权限清单（8 组 33 键，`*`=受保护）**：
overview.view ｜ user.view / user.update / user.disable / user.resetPassword / user.assignAdmin* ｜ permission.view / permission.edit* ｜ article.viewAll / article.viewOwn / article.create / article.manageOwn / article.manageAll / article.publish ｜ gallery.viewAll / gallery.viewOwn / gallery.create / gallery.manageOwn / gallery.manageAll ｜ diary.viewAll / diary.create / diary.manageOwn / diary.manageAll ｜ comment.viewAll / comment.moderate ｜ message.private / message.wallModerate ｜ file.view / file.upload / file.delete ｜ systemLog.view / system.setting / label.manage

默认矩阵：super_admin=全部；admin=除 `*` 外全部；editor=article(publish 除外全部)+gallery 全部+diary(create/manageOwn)+comment.viewAll+file.upload/view；viewer=无后台权限。

### 5.3 内容 content（article / image 共用）
| 方法 路径 | 权限 | 要点 |
| --- | --- | --- |
| GET /portal/contents | 公开 | ?contentType&labelId&keyword&page…；强制 state=published（**门户口径不随登录变化**，继承原项目决定） |
| GET /portal/contents/{id} | 公开 | 仅 published；?preview=1 时作者/管理者可看草稿；顺带记浏览 |
| GET /admin/contents | 对应 viewAll/viewOwn | 管理列表：无 viewAll 自动加 author_id=self |
| POST /admin/contents | article.create 或 gallery.create | 校验：title 1–150、introduce≤512、body≤200KB JSON；Tiptap JSON 结构校验（见 §8.4） |
| GET /admin/contents/{id} | viewAll 或 viewOwn+归属 | 不满足→40400 |
| PATCH /admin/contents/{id} | manageAll 或 manageOwn+归属 | 部分更新 |
| PATCH /admin/contents/{id}/state | manageAll/manageOwn + article.publish（发布/隐藏） | published↔hidden/draft；写 published_at |
| DELETE /admin/contents/{id} | manageAll/manageOwn | state=deleted；封面无引用则 media 转 trash（对应原 cleanupCover） |
| GET /labels ｜ POST/PATCH/DELETE /admin/labels/{id} | 公开读 ｜ label.manage 写 | 标签 CRUD，删除前检查引用数 |

### 5.4 日记 diary
GET /portal/diaries（公开，published）；GET/POST/PATCH/DELETE /admin/diaries[/{id}]（diary.* 键，同上 scope 规则）；weather 校验 8 值白名单。

### 5.5 评论与互动 comment
| 方法 路径 | 权限 | 要点 |
| --- | --- | --- |
| GET /portal/{targetType}/{id}/comments | 公开 | 返回一级+楼中楼两段（对应原 rootComments/replyComments）；仅 status=visible |
| POST /comments | 登录 | {targetType,targetId,parent_id?,replyToCommentId?,body≤2000}；目标存在且 visible；**举报本人评论 400**（继承） |
| DELETE /comments/{id} | 作者 或 comment.moderate | 一级删→连带回复逻辑删 + 回退两条冗余计数（事务） |
| POST /comments/{id}/praise | 登录 | toggle → {active, count} |
| POST /interactions | 登录 | {targetType:content\|diary, targetId} 点赞 toggle |
| POST /comments/{id}/report | 登录 | {reason∈白名单, detail≤200}；重复举报 40900 |
| GET /admin/comment-reports | comment.moderate | ?status=pending 默认；分页含评论与作者摘要 |
| PATCH /admin/comment-reports/{id} | comment.moderate | {action: resolved\|dismissed, note≤200} |
| POST /admin/comment-reports/batch | comment.moderate | {ids≤200, action}（对应原 batch 1–200） |

### 5.6 私信 message / 留言墙 guestbook
| 方法 路径 | 权限 | 要点 |
| --- | --- | --- |
| GET /messages/conversations | message.private | 会话列表按 last_message_at 倒序，含未读数 |
| GET /messages/conversations/{id} | 会话双方 | ?before= 游标分页（取 20 条更早） |
| POST /messages/conversations | 登录 | {recipientId, body}；无会话则建 |
| POST /messages/conversations/{id} | 双方 | 发送 + SSE 推送 |
| PATCH /messages/conversations/{id}/read | 双方 | 清未读 |
| GET /messages/unread-count | 登录 | 角标 |
| GET /guestbook | 公开 | 留言墙：全部 visible 一级 + 回复（保留原「主动留言全量可见」语义） |
| POST /guestbook | 登录 | {body, replyToId?} |
| DELETE /guestbook/{id} | 作者 或 message.wallModerate | 逻辑删 |

### 5.7 媒体 media / 文件 file
| 方法 路径 | 权限 | 要点 |
| --- | --- | --- |
| POST /files/upload | file.upload | multipart ≤20MB；Tika magic bytes 校验；图片走 sha256 去重 + 异步缩略图/WebP |
| GET /media/{storageKey} | 公开 | 图片原文/缩略 `?w=320|640|1280`，Cache-Control immutable 1y；非图片 403 |
| GET /admin/files | file.view | ?state&page；storage-status 汇总(用量/配额/备份区) |
| PATCH /admin/files/{id}/trash · /restore | file.delete | 软删进备份区 |
| DELETE /admin/files/{id} | file.delete | purge：删驱动对象 + 元数据 |
| GET /admin/files/storage-status | file.view | 主区/备份区字节、2GiB 配额、80% 告警 |

### 5.8 统计 / 审计 / 实时 / SEO
| 方法 路径 | 权限 | 要点 |
| --- | --- | --- |
| POST /views | 公开 | {targetType,targetId}；30 分钟去重后 +1（见 §8.6） |
| GET /admin/overview/summary | overview.view | 内容/评论/未读私信/文件量、今日与本周浏览 |
| GET /admin/overview/visit-stats | overview.view | ?range=week\|moon 折线 |
| GET /admin/system-logs | systemLog.view | ?module&action&page |
| GET /events | 登录 | **SSE**；事件：`message.new`、`report.new`(推给 moderate 持有者)、`heartbeat`；30s 心跳，断线浏览器自动重连 |
| GET /sitemap.xml · /robots.txt | 公开 | 由 published 内容动态生成；后台页面 noindex（继承原 SEO 约定） |

## 6. 前端路由与页面设计

### 6.1 路由表

**门户 PortalLayout（公开）**：
| 路径 | 组件 | 说明 |
| --- | --- | --- |
| / | PortalHome | 最新 + 置顶 + 标签云 |
| /articles · /articles/:id | ArticleList / ArticleDetail | 列表分页；详情 Tiptap JSON 渲染 |
| /gallery · /gallery/:id | GalleryWall / GalleryDetail | 瀑布流 + srcset 缩略图 |
| /diary | DiaryWall | 时间轴 + 天气/心情 |
| /guestbook | GuestbookWall | 留言墙 |
| /login-register · /reset-password | LoginRegister / ResetPassword | 登录注册二合一、重置流程 |
| /:pathMatch(.*)* | NotFound | 404 |

**后台 AdminLayout（requiresAuth + meta.permission）**：
| 路径 | 组件 | meta.permission |
| --- | --- | --- |
| /admin | OverView | overview.view |
| /admin/articles · /admin/articles/edit/:id? | ArticleManage / ArticleEdit | article.viewAll\|viewOwn ／ article.create\|manageOwn |
| /admin/gallery · /admin/gallery/edit/:id? | GalleryManage / GalleryEdit | gallery.* 同构 |
| /admin/diaries | DiaryManage | diary.viewAll\|viewOwn |
| /admin/comments | CommentManage | comment.viewAll |
| /admin/reports | ReportWorkbench | comment.moderate |
| /admin/messages · /admin/guestbook | MessageSessions / GuestbookManage | message.private / message.wallModerate |
| /admin/files | FileManager | file.view |
| /admin/users | UserManagement | user.view |
| /admin/permissions | PermissionMatrix | permission.view |
| /admin/logs · /admin/settings | SystemLog / Settings | systemLog.view / system.setting |

守卫：`router.beforeEach` → 初始化会话（localStorage 只做首屏快照，权限以 `GET /permissions/me` 为准）→ 逐条比对 meta.permission（超管短路）→ 未授权跳 404（**不暴露后台入口存在**）；`afterEach` 给后台路由设 noindex。

### 6.2 页面设计要点（每页 2–4 条）

| 页面 | 设计要点 |
| --- | --- |
| PortalHome | 首屏 LCP≤2.5s：首屏数据内联到 HTML 不行(SPA)→ 首屏接口合并为 `GET /portal/home` 单请求；图片 loading=lazy + srcset |
| ArticleDetail | Tiptap JSON 确定性渲染（**无需 DOMPurify**，蓝图默认值 3）；代码高亮按需；阅读进度条；评论区两段式（一级+楼中楼） |
| GalleryWall | 虚拟滚动 + `?w=` 缩略图三档；点击进详情/灯箱 |
| DiaryWall | 按日期倒序时间轴；天气 emoji 映射与 8 值白名单一致 |
| GuestbookWall | 全量主动留言 + 两级回复；发布框字数 2000 |
| ArticleEdit | Tiptap 按需扩展（段落/标题/引用/代码/图片/链接）；封面选择器复用媒体库；**路由级动态 import → 独立 chunk**，size-limit 单独预算；自动存草稿 30s |
| ReportWorkbench | pending 队列默认视图；卡片含评论上下文+原因+补充；单条/批量处理(resolved/dismissed)+备注≤200；新举报 SSE 角标即时刷新（对应原「跨组件事件即时刷新」） |
| MessageSessions | 左会话右消息；SSE 追加 + 未读清零；乐观发送失败回滚 |
| PermissionMatrix | 角色卡 × 8 分组矩阵；受保护格锁定；整列授予；非超管只读（保存即 PUT matrix） |
| UserManagement | 搜索/状态筛选；禁用即时生效提示「该用户全部登录已失效」 |
| FileManager | 列表 + trash 切换 + storage-status 面板（配额进度条、80% 告警红条） |
| OverView | ECharts 动态 import；visit-stats week/moon 切换 |
| LoginRegister | 登录/注册 Tab；密码强度条；错误壳字段级提示 |

## 7. 关键机制设计

1. **认证链**：`JwtCookieFilter` 读 `linlog_at`（兼容 `Authorization: Bearer` 供脚本调用）→ 验签 → `users.status/token_version` 校验（每请求一次 DB 查，命中 Caffeine 30s 短缓存）→ 注入 `authorities`（角色权限键集合，来自 Caffeine 权限缓存）。矩阵保存 → `cache.invalidateAll()`，秒级生效（对应原 refreshPermissionCache）。
2. **刷新轮换**：`POST /auth/refresh` 事务内：校验 rt hash → 已 revoked ⇒ **重放**：吊销 family + token_version+1 + 审计告警；否则 revoke 旧、发新、写 rotated_to_id。前端 fetch 封装收到 40101 单飞刷新并重放队列。
3. **防探测**：内容/日记读取类权限不足一律 40400；管理端操作类才 40300（继承原项目口径）。
4. **Tiptap JSON 服务端校验**：自定义 `ContentJsonValidator`（递归白名单 node type/attribute，深度 ≤30，body ≤200KB）；`plain_text` 由服务端提取，杜绝恶意属性入库——**消毒发生在写入时而非每次渲染**。
5. **图片管线**：上传 → Tika 探型 → sha256 去重 → LocalDriver/S3Driver 原图落盘 → 事务提交后异步（虚拟线程）生成 320/640/1280 WebP 缩略图落盘 → 回写 thumb_key；封面删除时无引用则转 trash（对应原 deactivateImageIfUnreferenced）。
6. **浏览去重**：`POST /views` → visitor_key（登录 SHA-256(`u:{id}`)，游客 SHA-256(IP+UA)）→ 查 `view_logs` 30 分钟窗口内同目标是否存在 → 无则插入并 `content.views+1`（窗口内并发重复最多多记一次，业务可接受，不为它加锁）。
7. **SSE**：`SseEmitter` 按 userId 注册；私信/举报事件即发；30s 心跳防代理断连；Caddy 侧 `flush_interval -1`。规模假设 ≤ 并发百人，单机内存注册表足够（复杂度克制）。
8. **限流**：Bucket4j 进程内令牌桶，仅挂 login / register / reset-*（IP 维度）；全站不设（个人博客自用）。
9. **定时任务**（@Scheduled，单机自锁无需分布式锁）：审计 90 天清理、view_logs 7 天、trash 30 天 purge、备份区 2GiB 检查告警。
10. **备份**：compose 内 `mysqldump` cron + storage 目录 rsync 异地副本（沿用原「跨盘镜像+S3」思路）。

## 8. 测试设计

### 8.1 分层与工具（后端）

| 层 | 工具 | 对象 |
| --- | --- | --- |
| 单元 | JUnit5 + Mockito | Service 业务规则（权限 scope、计数回退、轮换状态机、JSON 校验器） |
| 仓库 | Testcontainers-MySQL + **Flyway 真实迁移** | 每条 SQL/索引行为（ngram 检索、函数唯一索引、游标分页） |
| Web 切片 | MockMvc + 自定义 `@WithLoginUser(role, perms)` | 参数校验、错误壳、权限判定 |
| 安全专项 | MockMvc | 404 防探测、token_version 失效、refresh 重放、XSRF 缺失 403、限流 429 |
| 集成 | @SpringBootTest 全链路 | 注册→登录→发文→评论→举报→处理→SSE 收到事件 |

### 8.2 用例清单（目标 ≈340，对齐原项目 337 的量级）

| 域 | 数量 | 覆盖重点 |
| --- | --- | --- |
| auth | 45 | 并发注册 409、重放检测家族吊销、改密全端下线、重置一次性、限流 |
| permission | 30 | 33 键默认矩阵、受保护键拒绝、缓存失效秒级生效、非超管 PUT 403 |
| content/article+gallery | 65 | scope 四态(viewAll/viewOwn 组合)、状态机、封面软删、门户口径恒 published、ngram 检索 |
| diary | 20 | 一日一篇唯一、天气白名单 |
| comment | 45 | 两级结构、级联逻辑删+计数回退 GREATEST、自举报 400、praise toggle |
| reports | 25 | 状态机 pending→resolved/dismissed、批量 1–200、重复举报 409 |
| message/guestbook | 30 | 会话配对唯一、未读数、游标分页、留言墙可见性 |
| media/file | 30 | magic bytes 拒伪装、sha256 去重复用、缩略图三档、trash/restore/purge、配额告警 |
| stats/audit/seo | 25 | 去重窗口、保留清理、sitemap 只含 published、审计字段完整 |
| security 专项 | 25 | 见 8.1 |

### 8.3 前端测试（≈70）

- **单测 Vitest**：session store（can() 超管短路、刷新失败保留旧快照——继承原语义）、fetch 封装（40101 单飞刷新、重放队列、40102 踢出）、renderContent 遍历器、天气映射。
- **组件测试**：PermissionMatrix（受保护格锁定）、ReportWorkbench（批量选择上限 200）、CommentTree（两级渲染/回复定位）。
- **E2E Playwright**（12 条）：注册登录登出、发文→门户可见、评论→举报→后台处理闭环、上传→trash→restore、私信实时到达（双 context）、权限变更即时生效、移动端视口门户冒烟。

### 8.4 CI 门禁（GitHub Actions）

| Job | 步骤 | 阈值 |
| --- | --- | --- |
| backend | mvn verify（Testcontainers）+ JaCoCo | 核心包行覆盖 ≥70% |
| frontend | pnpm lint / vitest / build + size-limit | 首屏 ≤300KB gzip；editor chunk ≤500KB；总 JS ≤1.2MB |
| contract | 后端产出 openapi.json → 前端 gen:api → `git diff --exit-code` | 契约漂移即红 |
| e2e | Playwright（schedule 触发，PR 只跑 12 条冒烟） | — |

## 9. 技术与技能矩阵（用到的 skill）

| 领域 | 具体技能 | 用在哪里 | 建议水平 |
| --- | --- | --- | --- |
| Java 工程 | Java 21、Maven、虚拟线程 | 全部后端 | 熟练 |
| Spring 生态 | Boot 3 / Security 过滤链 / Validation / springdoc / @Scheduled | 认证、权限、API 层 | 熟练 |
| SQL | MySQL 8：函数唯一索引、ngram 全文、窗口/游标分页、索引设计 | §3 数据库 | 熟练（本项目最吃重处） |
| MyBatis-Plus | Wrapper 构建器、分页插件、乐观锁 | 模型层 | 会用 |
| 安全 | bcrypt、JWT 轮换/重放检测、CSRF 双提交、CORS 精确来源、Tika 校验、防探测 | §4/§7 | 理解原理（原项目已验证过这套直觉，直接迁移） |
| 前端 | Vue3 组合式 API、Pinia、Router 守卫、Vite 分包 | 全部前端 | 熟练 |
| 富文本 | Tiptap schema/扩展开发、JSON↔VNode 渲染 | 编辑器/详情页 | 会用+一次扩展开发 |
| 实时 | SSE / SseEmitter、Caddy 透传 | 私信/举报角标 | 会用 |
| 测试 | Testcontainers、MockMvc、Vitest、Playwright、覆盖率门禁 | §8 | 熟练（原项目「修复必配测试」纪律延续） |
| 运维 | docker compose、Caddy、备份脚本、GitHub Actions | deploy/ + CI | 会用 |

## 10. 里程碑（沿用被验证过的搭建顺序）

| 迭代 | 内容 | DoD |
| --- | --- | --- |
| 0 骨架 | monorepo、CI 双 job、compose(MySQL)、Flyway V1、健康检查 | CI 绿；`docker compose up` 可起 |
| 1 认证 | users/refresh/reset + Cookie 链 + 刷新轮换 + XSRF | auth 45 测全绿；改密全端下线 |
| 2 内容域 | content/labels/subsets + 上传/媒体管线 + 编辑器 | 发文→门户可见；缩略图三档落盘 |
| 3 互动 | 统一评论/点赞/举报 + 留言墙 | 级联删除计数一致；举报闭环 |
| 4 后台 | 管理列表/编辑页/文件管理/概览统计 | 全部后台页过权限矩阵 |
| 5 权限体系 | 矩阵页 + Caffeine 失效 + 审计/系统日志 | 变更秒级生效；防探测用例绿 |
| 6 私信+实时 | 会话/SSE + 留言墙完善 | 双端消息 <2s 到达 |
| 7 收尾 | SEO/sitemap、备份任务、性能预算、E2E | size-limit/Lighthouse 达标；compose 一键部署验收 |

## 11. 风险与验收

**风险**：① webp-imageio 编码质量/性能不如 sharp——验收时对比画质，必要时改 `imgproxy` 容器；② Tiptap JSON 校验器遗漏属性——以白名单+单测枚举兜底；③ SSE 过反代缓冲——Caddy `flush_interval -1` 写进 Caddyfile 并 E2E 验证；④ Testcontainers 拖慢 CI——缓存镜像层、并行 job；⑤ ngram 分词默认 2 元——按需调 `ngram_token_size`。
**验收（A1–A8）**：A1 未认证/过期/重放三类凭证行为正确；A2 权限矩阵保存 5 秒内生效且受保护键不可改；A3 文章发布全流程含封面去重；A4 评论级联删除后计数与明细一致；A5 举报处理单条+批量含备注；A6 上传 trash/restore/purge 与配额告警；A7 私信 SSE 实时到达且刷新后未读一致；A8 CI 全门禁绿 + compose 一键起 + 首屏/editor 体积达标。

## 12. 维护约定

本文管「将要做的重实现」；《重设计蓝图》管「为什么」；《项目立案》管「现行项目」。三线并行互不覆盖：若决定真正启动重实现，应先在立案文档新开 M 系列里程碑，再以本文为施工图；实现过程中与本文冲突的，改本文并记变更日期。
