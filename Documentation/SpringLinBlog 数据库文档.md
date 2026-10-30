# SpringLinBlog 数据库文档

> 本文档描述 **SpringLinBlog**（LinBlog · Vue3 + Spring Boot 重实现）的数据库设计。
> 唯一事实来源为迁移脚本 `backend/src/main/resources/db/migration/V1__init.sql`（22 张表一次建齐），
> 并与 `backend/src/main/java/com/linblog/**` 下的实体 / Mapper 交叉核对。
> 若本文档与迁移脚本冲突，**一律以迁移脚本为准**（差异见 [§6 已知差异与待确认](#六已知差异与待确认)）。

---

## 目录导航

- [一、文档概述](#一文档概述)
  - [1.1 文档目的](#11-文档目的)
  - [1.2 数据库基础信息](#12-数据库基础信息)
  - [1.3 全局约定](#13-全局约定)
- [二、连接与迁移](#二连接与迁移)
  - [2.1 连接串](#21-连接串)
  - [2.2 连接池（HikariCP）](#22-连接池hikaricp)
  - [2.3 Flyway 迁移机制](#23-flyway-迁移机制)
  - [2.4 MySQL 服务端参数](#24-mysql-服务端参数)
- [三、表结构总览](#三表结构总览)
  - [3.1 按业务域分组](#31-按业务域分组)
  - [3.2 表清单一览](#32-表清单一览)
  - [3.3 表关系说明](#33-表关系说明)
- [四、逐表详述](#四逐表详述)
  - [4.1 账号与权限域](#41-账号与权限域)
    - [4.1.1 users — 用户](#411-users--用户)
    - [4.1.2 refresh_tokens — 刷新令牌](#412-refresh_tokens--刷新令牌)
    - [4.1.3 password_reset_tokens — 密码重置令牌](#413-password_reset_tokens--密码重置令牌)
    - [4.1.4 role_permissions — 权限矩阵](#414-role_permissions--权限矩阵)
  - [4.2 内容域](#42-内容域)
    - [4.2.1 content — 文章 + 图库统一内容](#421-content--文章--图库统一内容)
    - [4.2.2 media — 图片/文件元数据](#422-media--图片文件元数据)
    - [4.2.3 labels — 标签](#423-labels--标签)
    - [4.2.4 content_labels — 内容-标签关联](#424-content_labels--内容-标签关联)
    - [4.2.5 subsets — 合集](#425-subsets--合集)
    - [4.2.6 subset_items — 合集条目](#426-subset_items--合集条目)
    - [4.2.7 diaries — 日记](#427-diaries--日记)
  - [4.3 互动域](#43-互动域)
    - [4.3.1 interactions — 内容/日记点赞](#431-interactions--内容日记点赞)
    - [4.3.2 comments — 统一评论](#432-comments--统一评论)
    - [4.3.3 comment_praises — 评论点赞](#433-comment_praises--评论点赞)
    - [4.3.4 comment_reports — 评论举报](#434-comment_reports--评论举报)
  - [4.4 私信与留言墙域](#44-私信与留言墙域)
    - [4.4.1 conversations — 私信会话](#441-conversations--私信会话)
    - [4.4.2 direct_messages — 私信](#442-direct_messages--私信)
    - [4.4.3 guestbook — 留言墙](#443-guestbook--留言墙)
  - [4.5 文件域](#45-文件域)
    - [4.5.1 files — 后台通用附件](#451-files--后台通用附件)
  - [4.6 系统与审计域](#46-系统与审计域)
    - [4.6.1 view_logs — 浏览统计](#461-view_logs--浏览统计)
    - [4.6.2 request_audits — 请求审计](#462-request_audits--请求审计)
    - [4.6.3 system_logs — 业务操作日志](#463-system_logs--业务操作日志)
- [五、维护与调试](#五维护与调试)
- [六、已知差异与待确认](#六已知差异与待确认)

---

## 一、文档概述

### 1.1 文档目的

本文档供开发、测试与运维人员完整查阅 SpringLinBlog 的数据库结构，包括：

- 数据库基础信息（库名、字符集、版本、连接池、迁移机制）；
- 22 张表的字段级定义、索引与业务含义；
- 表间关联关系与数据生命周期策略；
- 已知的 SQL / Java 侧差异与尚待确认的点。

### 1.2 数据库基础信息

| 项目 | 值 | 来源 |
| --- | --- | --- |
| 数据库名 | `springlinblog` | `deploy/docker-compose.yml:20`、`deploy/ci.env:4` |
| 数据库类型 | MySQL 8.4 | `deploy/docker-compose.yml:13`（`image: mysql:8.4`） |
| 字符集 | `utf8mb4` | `V1__init.sql` 每张表 `DEFAULT CHARSET = utf8mb4`；服务端 `deploy/docker-compose.yml:15` |
| 排序规则 | `utf8mb4_0900_ai_ci` | `V1__init.sql` 每张表 `COLLATE = utf8mb4_0900_ai_ci`；服务端 `deploy/docker-compose.yml:16` |
| ngram 分词长度 | `ngram_token_size = 2` | `deploy/docker-compose.yml:17`（配合 `content.ft_search` 中文全文检索） |
| 存储引擎 | InnoDB | `V1__init.sql` 每张表 `ENGINE = InnoDB` |
| 迁移工具 | Flyway（`flyway-core` + `flyway-mysql`） | `backend/pom.xml:76-82` |
| 迁移脚本目录 | `classpath:db/migration` | `backend/src/main/resources/application.yml:20` |
| 基线策略 | `baseline-on-migrate: false` | `backend/src/main/resources/application.yml:21` |
| 连接池 | HikariCP，`maximum-pool-size: 10` / `minimum-idle: 2` | `backend/src/main/resources/application.yml:16-18` |
| JDBC 驱动 | `com.mysql:mysql-connector-j` | `backend/pom.xml:69-72` |
| ORM | MyBatis-Plus（`map-underscore-to-camel-case: true`） | `backend/src/main/resources/application.yml:29-33` |

> ⚠️ 数据库名一律为 **`springlinblog`**。旧版 Express 实现使用的 `linblog` / `linlog` 命名均已废弃。

### 1.3 全局约定

以下约定由 `V1__init.sql:1-7` 的脚本头注释明确声明，贯穿全部 22 张表：

1. **主键**：`id BIGINT UNSIGNED AUTO_INCREMENT`；部分表采用复合主键（见各表说明）。
2. **时间列**：统一 `DATETIME(3)`（毫秒精度）。
3. **字符集/排序规则**：表级 `utf8mb4` / `utf8mb4_0900_ai_ci`。
4. **不建物理外键**：所有跨表引用只建普通索引（`KEY`），由应用层保证引用完整性——便于归档与迁移。
5. **软删用状态列**：不使用 `deleted_at`，改用 `state` / `status` 等状态字段表达逻辑删除。
6. **计数冗余**：`content.views` / `content.praises` / `content.comments_count` / `comments.praise_count` 等为冗余计数字段，明细表为准，事务内回退。

---

## 二、连接与迁移

### 2.1 连接串

统一示例（本地）：

```
jdbc:mysql://localhost:3306/springlinblog?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
```

各环境实际取值：

| 环境 | `DB_URL` | 来源文件 |
| --- | --- | --- |
| 本地开发（root/root） | `jdbc:mysql://localhost:3306/springlinblog?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai` | `backend/src/main/resources/application-dev.yml:5` |
| 后端 `.env` 样例 | 同上 | `backend/.env.example:9` |
| 容器部署（compose 网络） | `jdbc:mysql://mysql:3306/springlinblog?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai` | `deploy/.env.example:25` |
| CI / e2e | 同容器部署 | `deploy/ci.env:5` |

配套账号：

- 本地开发：`DB_USERNAME=root` / `DB_PASSWORD=root`（`application-dev.yml:6-7`）。
- 容器部署：`DB_USERNAME=springlinblog`（`deploy/.env.example:27`、`deploy/ci.env:6`），MySQL 容器以该账号创建同名库与用户（`deploy/docker-compose.yml:21-22`）。
- 变量缺失时后端**拒绝启动**（`application.yml:13-15` 全部为空占位，由 `AppStartupGuard` 强校验）。

> `useUnicode=true&characterEncoding=utf8` 是 JDBC 层的参数；真正的存储字符集由服务端与表级 `utf8mb4` 保证，`characterEncoding=utf8` 在 MySQL Connector/J 8.x 下映射到 `utf8mb4` 处理。
> `serverTimezone=Asia/Shanghai` 与容器 `TZ=Asia/Shanghai`（`deploy/docker-compose.yml:23`）保持一致。

### 2.2 连接池（HikariCP）

`backend/src/main/resources/application.yml:16-18`：

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
```

| 参数 | 值 | 说明 |
| --- | --- | --- |
| `maximum-pool-size` | 10 | 单应用最大活跃连接数 |
| `minimum-idle` | 2 | 最小空闲连接数 |
| `connection-timeout` | 未显式配置 | 使用 Hikari 默认 30s（**待确认**，见 §6） |
| `max-lifetime` | 未显式配置 | 使用 Hikari 默认 30min（**待确认**，见 §6） |

### 2.3 Flyway 迁移机制

| 配置项 | 值 | 来源 |
| --- | --- | --- |
| `spring.flyway.locations` | `classpath:db/migration` | `application.yml:20` |
| `spring.flyway.baseline-on-migrate` | `false` | `application.yml:21` |
| `spring.flyway.enabled` | 未显式配置 → 默认 `true` | — |
| 迁移依赖 | `flyway-core` + `flyway-mysql` | `backend/pom.xml:75-82` |

行为要点：

- 迁移脚本位于 `backend/src/main/resources/db/migration/`，当前**仅有 `V1__init.sql`** 一个版本脚本，一次建齐 22 张表。
- `baseline-on-migrate: false` 表示**禁止**对非空 schema 做隐式基线：若目标库 `springlinblog` 已存在且 `flyway_schema_history` 中没有 V1 记录，Flyway 迁移会因「非空 schema」直接失败，需要人工介入（清库重建，或手工 `flyway baseline` 后补记历史）。
- 因此**新增/修改表结构的标准流程**是：在 `db/migration/` 下新增 `V2__xxx.sql`、`V3__xxx.sql` 递增版本脚本，禁止修改已发布的 `V1__init.sql`。
- 表结构文档以 `V1__init.sql` 为准；新增迁移后需同步更新本文档。

### 2.4 MySQL 服务端参数

`deploy/docker-compose.yml:13-17`：

```yaml
mysql:
  image: mysql:8.4
  command:
    - --character-set-server=utf8mb4
    - --collation-server=utf8mb4_0900_ai_ci
    - --ngram_token_size=2
```

- `--character-set-server=utf8mb4` / `--collation-server=utf8mb4_0900_ai_ci`：默认库与默认表字符集/排序规则，与迁移脚本中的表级声明一致。
- `--ngram_token_size=2`：ngram 全文解析器的分词粒度为 2 个字符，是中文检索（`content.ft_search`）可用且可控的关键参数。**该参数在服务启动时确定**，修改后需要重建索引（`FULLTEXT` 索引需 drop 后重建）才能生效。

---

## 三、表结构总览

### 3.1 按业务域分组

| 业务域 | 表 | 表数 |
| --- | --- | --- |
| **账号与权限** | `users`、`refresh_tokens`、`password_reset_tokens`、`role_permissions` | 4 |
| **内容** | `content`、`media`、`labels`、`content_labels`、`subsets`、`subset_items`、`diaries` | 7 |
| **互动** | `interactions`、`comments`、`comment_praises`、`comment_reports` | 4 |
| **私信与留言墙** | `conversations`、`direct_messages`、`guestbook` | 3 |
| **文件** | `files` | 1 |
| **系统与审计** | `view_logs`、`request_audits`、`system_logs` | 3 |
| **合计** | — | **22** |

### 3.2 表清单一览

| # | 表名 | 所属域 | 中文名 | 主键 | 表注释（SQL 原文） |
| --- | --- | --- | --- | --- | --- |
| 1 | `users` | 账号与权限 | 用户 | `id` | 用户 |
| 2 | `refresh_tokens` | 账号与权限 | 刷新令牌 | `id` | 刷新令牌（轮换+复用检测） |
| 3 | `password_reset_tokens` | 账号与权限 | 密码重置令牌 | `id` | 密码重置令牌 |
| 4 | `role_permissions` | 账号与权限 | 权限矩阵持久层 | `(role_code, perm_key)` | 权限矩阵持久层（覆盖值） |
| 5 | `content` | 内容 | 文章+图库统一内容 | `id` | 文章+图库统一内容 |
| 6 | `media` | 内容 | 图片/文件元数据 | `id` | 图片/文件元数据 |
| 7 | `labels` | 内容 | 标签 | `id` | 标签 |
| 8 | `content_labels` | 内容 | 内容-标签关联 | `(content_id, label_id)` | 内容-标签关联 |
| 9 | `subsets` | 内容 | 合集 | `id` | 合集 |
| 10 | `subset_items` | 内容 | 合集条目 | `(subset_id, content_id)` | 合集条目 |
| 11 | `diaries` | 内容 | 日记 | `id` | 日记 |
| 12 | `interactions` | 互动 | 内容/日记点赞 | `id` | 内容/日记点赞 |
| 13 | `comments` | 互动 | 统一评论 | `id` | 统一评论 |
| 14 | `comment_praises` | 互动 | 评论点赞 | `(comment_id, user_id)` | 评论点赞 |
| 15 | `comment_reports` | 互动 | 评论举报 | `id` | 评论举报 |
| 16 | `conversations` | 私信与留言墙 | 私信会话 | `id` | 私信会话 |
| 17 | `direct_messages` | 私信与留言墙 | 私信 | `id` | 私信 |
| 18 | `guestbook` | 私信与留言墙 | 留言墙一级留言 | `id` | 留言墙一级留言（回复走 comments） |
| 19 | `files` | 文件 | 后台通用附件 | `id` | 后台通用附件（单文件 ≤20MB、备份区 2GiB、trash 30 天） |
| 20 | `view_logs` | 系统与审计 | 业务浏览统计 | `id` | 业务浏览统计（30 分钟去重） |
| 21 | `request_audits` | 系统与审计 | 请求审计 | `id` | 请求审计（原 record 表，90 天滚动清理） |
| 22 | `system_logs` | 系统与审计 | 业务操作日志 | `id` | 业务操作日志 |

### 3.3 表关系说明

**引用均以索引表达，不建物理外键**（`V1__init.sql:5`）。

```
users ──┬─< refresh_tokens        (user_id)
        ├─< password_reset_tokens (user_id)
        ├─< content               (author_id)
        ├─< media                 (uploader_id, content_id)
        ├─< labels?  ──< content_labels >── content
        ├─< subsets               (owner_id, cover_media_id)
        ├─< subset_items >──────── content
        ├─< diaries               (author_id)
        ├─< interactions           (user_id)
        ├─< comments               (author_id, parent_id, reply_to_comment_id, reply_to_user_id)
        ├─< comment_praises        (user_id)
        ├─< comment_reports        (reporter_id, handled_by)
        ├─< conversations          (user_a_id, user_b_id)
        ├─< direct_messages        (sender_id)
        ├─< guestbook              (user_id)
        ├─< files                  (uploader_id)
        ├─< view_logs              (user_id)
        ├─< request_audits         (user_id)
        └─< system_logs            (user_id)

media   ──< content.cover_media_id / users.avatar_media_id / subsets.cover_media_id
content ──< comments.target_id / interactions.target_id / subset_items.content_id
diaries ──< comments.target_id / interactions.target_id
guestbook ─< comments.target_id
conversations ──< direct_messages.conversation_id
```

**多态引用说明**（跨表复用同一列，需按前缀区分目标表）：

| 列 | 取值 | 目标表 |
| --- | --- | --- |
| `content.content_type` | `article` / `image` | 决定 `content` 行表示文章还是图库项 |
| `interactions.target_type` | `content` / `diary` | `ENUM`，`target_id` → `content.id` 或 `diaries.id` |
| `comments.target_type` | `content` / `diary` / `guestbook` | `VARCHAR(10)`，`target_id` → 对应表主键 |
| `view_logs.target_type` | `content` / `diary` / `portal` | `ENUM`，`portal` 时 `target_id` 为 NULL |
| `system_logs.target_type` | 自由文本 | `NULL` 时表示无具体对象 |

---

## 四、逐表详述

### 4.1 账号与权限域

#### 4.1.1 users — 用户

> **用途**：账号主体，承载登录凭据、角色与状态；**主键**：`id`（BIGINT UNSIGNED AUTO_INCREMENT）；**索引**：`uk_username`、`uk_email`、`uk_single_super_admin`（函数唯一索引）；**关联**：`media.id`（头像）、`refresh_tokens`、`password_reset_tokens`、`content`/`diaries` 等作者列。

| 字段名 | 类型 | 允许空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 用户 ID，主键 |
| username | VARCHAR(50) | 否 | — | 登录名，正则约束 `^[a-zA-Z0-9_]{3,20}$`（应用层校验），唯一 |
| email | VARCHAR(100) | 否 | — | 邮箱，唯一 |
| password_hash | VARCHAR(100) | 否 | — | bcrypt 哈希，cost 12 |
| nickname | VARCHAR(50) | 是 | NULL | 展示名 |
| avatar_media_id | BIGINT UNSIGNED | 是 | NULL | 头像，关联 `media.id` |
| role_code | VARCHAR(20) | 否 | `'viewer'` | 角色：`super_admin` / `admin` / `editor` / `viewer` |
| is_super_admin | TINYINT(1) | 否 | （生成列） | **STORED 生成列**：`CASE WHEN role_code = 'super_admin' THEN 1 ELSE 0 END`；Java 侧不读写 |
| status | TINYINT(1) | 否 | `1` | 1 正常 / 0 禁用 |
| token_version | INT | 否 | `0` | 改密/重置时 +1，使旧 access 令牌全部失效 |
| created_at | DATETIME(3) | 否 | `CURRENT_TIMESTAMP(3)` | 创建时间 |
| updated_at | DATETIME(3) | 否 | `CURRENT_TIMESTAMP(3)` ON UPDATE | 更新时间，自动刷新 |

**索引**

| 索引名 | 类型 | 列 | 说明 |
| --- | --- | --- | --- |
| PRIMARY | 主键 | id | — |
| uk_username | UNIQUE | username | 登录名唯一 |
| uk_email | UNIQUE | email | 邮箱唯一 |
| uk_single_super_admin | UNIQUE（函数索引） | `(CASE WHEN is_super_admin = 1 THEN 1 END)` | MySQL 8 函数索引：非超管表达式结果为 NULL，唯一约束允许多个 NULL → **数据库层保证全库至多一个超管** |

**关联的 Java 侧**

- 实体 `backend/src/main/java/com/linblog/module/user/entity/User.java`（`@TableName("users")`），字段与本表一一对应，**不含** `is_super_admin`（生成列不参与读写）。
- Mapper `backend/src/main/java/com/linblog/module/user/mapper/UserMapper.java:13-14`：`bumpTokenVersion` 执行 `UPDATE users SET token_version = token_version + 1 WHERE id = #{id}`。

**补充说明**

- 超管种子数据不在 SQL 中，由 `backend/src/main/java/com/linblog/module/user/service/SuperAdminSeeder.java` 在首次启动时以 `ApplicationRunner` 写入（bcrypt 哈希不适合硬编码进 SQL）。
- `role_permissions` 表只存 admin / editor / viewer 三种可配置角色的覆盖值；`super_admin` 的键集在代码常量 `PermissionKeys` 中硬编码，不落表。

#### 4.1.2 refresh_tokens — 刷新令牌

> **用途**：refresh token 的轮换与复用检测存储，只落 SHA-256 哈希；**主键**：`id`；**索引**：`uk_token_hash`、`idx_user(user_id, expires_at)`、`idx_family(family_id)`；**关联**：`users.id`（user_id）、`refresh_tokens.id`（rotated_to_id 指向轮换后的新令牌）。

| 字段名 | 类型 | 允许空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 令牌 ID，主键 |
| user_id | BIGINT UNSIGNED | 否 | — | 所属用户，关联 `users.id` |
| family_id | CHAR(36) | 否 | — | UUID；同一登录会话的令牌归为一族 |
| token_hash | CHAR(64) | 否 | — | SHA-256 十六进制；明文仅存在于 Cookie |
| expires_at | DATETIME(3) | 否 | — | 过期时间，默认有效期 7 天（`REFRESH_TTL_DAYS=7`） |
| revoked_at | DATETIME(3) | 是 | NULL | 吊销时间；NULL 表示有效 |
| rotated_to_id | BIGINT UNSIGNED | 是 | NULL | 轮换后指向的新 token id |
| created_ip | VARCHAR(45) | 是 | NULL | 创建来源 IP（兼容 IPv6，45 字符） |
| created_ua_hash | CHAR(32) | 是 | NULL | SHA-256(UA) 十六进制 |
| created_at | DATETIME(3) | 否 | `CURRENT_TIMESTAMP(3)` | 创建时间 |

**索引**

| 索引名 | 类型 | 列 | 说明 |
| --- | --- | --- | --- |
| PRIMARY | 主键 | id | — |
| uk_token_hash | UNIQUE | token_hash | 哈希唯一，防止重复入库 |
| idx_user | 普通 | (user_id, expires_at) | 按用户查有效令牌 / 过期清理 |
| idx_family | 普通 | family_id | 复用检测时按族批量吊销 |

**关联的 Java 侧**：`backend/src/main/java/com/linblog/module/auth/entity/RefreshToken.java`、`.../mapper/RefreshTokenMapper.java`：

- `markRotated`：仅当 `revoked_at IS NULL` 时才写入 `rotated_to_id` / `revoked_at`（并发重放时 affected=0）。
- `revokeFamily`：按 `family_id` 吊销整族。
- `revokeAllForUser`：按 `user_id` 吊销全部（改密 / 禁用 / 重置密码时调用）。
- `deleteExpiredBefore`：清理 `expires_at < before` 的过期行。

#### 4.1.3 password_reset_tokens — 密码重置令牌

> **用途**：密码重置一次性令牌；**主键**：`id`；**索引**：`uk_token_hash`、`idx_user(user_id)`；**关联**：`users.id`（user_id）。

| 字段名 | 类型 | 允许空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 令牌 ID，主键 |
| user_id | BIGINT UNSIGNED | 否 | — | 所属用户，关联 `users.id` |
| token_hash | CHAR(64) | 否 | — | SHA-256 十六进制，唯一 |
| expires_at | DATETIME(3) | 否 | — | 过期时间，默认 30 分钟 |
| used_at | DATETIME(3) | 是 | NULL | 使用时间；一次性，用后即写 |
| created_at | DATETIME(3) | 否 | `CURRENT_TIMESTAMP(3)` | 创建时间 |

**索引**

| 索引名 | 类型 | 列 | 说明 |
| --- | --- | --- | --- |
| PRIMARY | 主键 | id | — |
| uk_token_hash | UNIQUE | token_hash | 哈希唯一 |
| idx_user | 普通 | user_id | 按用户作废旧令牌 |

**关联的 Java 侧**：`backend/src/main/java/com/linblog/module/auth/entity/PasswordResetToken.java`、`.../mapper/PasswordResetTokenMapper.java`：

- `markUsed`：`SET used_at = ? WHERE id = ? AND used_at IS NULL`（并发下 affected=0 视为已用）。
- `invalidateAllForUser`：新请求作废该用户全部未使用令牌。

#### 4.1.4 role_permissions — 权限矩阵

> **用途**：权限矩阵的**持久化覆盖值**（角色有行 → 以表值为准；无行 → 使用代码内默认矩阵）；**主键**：`(role_code, perm_key)` 联合主键，无自增 id；**索引**：仅联合主键，无额外二级索引；**关联**：逻辑关联 `users.role_code`。

| 字段名 | 类型 | 允许空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| role_code | VARCHAR(20) | 否 | — | 角色码，联合主键之一 |
| perm_key | VARCHAR(40) | 否 | — | 权限键，联合主键之一，如 `article.create` |

**索引**

| 索引名 | 类型 | 列 | 说明 |
| --- | --- | --- | --- |
| PRIMARY | 主键（联合） | (role_code, perm_key) | 同一角色同一权限键只存一行 |

**关联的 Java 侧**：`backend/src/main/java/com/linblog/module/permission/mapper/RolePermissionMapper.java`

- `selectKeysByRole(roleCode)` → `SELECT perm_key FROM role_permissions WHERE role_code = #{roleCode}`
- `deleteByRole(roleCode)` / `insert(roleCode, permKey)`

**权限键清单**（代码常量 `backend/src/main/java/com/linblog/module/permission/PermissionKeys.java`，8 组 33 键）：

| 组 | 权限键 |
| --- | --- |
| 概览 | `overview.view` |
| 用户 | `user.view`、`user.update`、`user.disable`、`user.resetPassword`、`user.assignAdmin` |
| 权限 | `permission.view`、`permission.edit` |
| 内容（文章） | `article.viewAll`、`article.viewOwn`、`article.create`、`article.manageOwn`、`article.manageAll`、`article.publish` |
| 内容（图库） | `gallery.viewAll`、`gallery.viewOwn`、`gallery.create`、`gallery.manageOwn`、`gallery.manageAll` |
| 日记 | `diary.viewAll`、`diary.create`、`diary.manageOwn`、`diary.manageAll` |
| 评论 | `comment.viewAll`、`comment.moderate` |
| 私信 | `message.private`、`message.wallModerate` |
| 文件系统与系统 | `file.view`、`file.upload`、`file.delete`、`systemLog.view`、`system.setting`、`label.manage` |

> 受保护键（仅 super_admin 持有，矩阵编辑接口拒绝修改）：`permission.edit`、`user.assignAdmin`。

---

### 4.2 内容域

#### 4.2.1 content — 文章 + 图库统一内容

> **用途**：文章与图库项的统一内容表（正式化原 `article.classify` 字段）；**主键**：`id`；**索引**：`idx_list(content_type, state, published_at DESC)`、`idx_author(author_id, state)`、全文索引 `ft_search(title, plain_text) WITH PARSER ngram`；**关联**：`users.id`（author_id）、`media.id`（cover_media_id）、`labels`（经 `content_labels`）、`subsets`（经 `subset_items`）。

| 字段名 | 类型 | 允许空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 内容 ID，主键 |
| author_id | BIGINT UNSIGNED | 否 | — | 作者，关联 `users.id` |
| content_type | VARCHAR(10) | 否 | — | `article` / `image` |
| title | VARCHAR(150) | 否 | — | article 必填；image 存图注标题 |
| introduce | VARCHAR(512) | 是 | NULL | 摘要 |
| body | MEDIUMTEXT | 是 | NULL | Tiptap JSON 正文；image 类型为 NULL（图片走 `media` 关联） |
| plain_text | MEDIUMTEXT | 是 | NULL | 服务端从 JSON 提取的纯文本，**仅供全文检索** |
| cover_media_id | BIGINT UNSIGNED | 是 | NULL | 封面图，关联 `media.id` |
| state | VARCHAR(10) | 否 | `'draft'` | `draft` / `published` / `hidden` / `deleted` |
| published_at | DATETIME(3) | 是 | NULL | 发布时间，发布时写入 |
| views | INT UNSIGNED | 否 | `0` | 浏览数（冗余计数） |
| praises | INT UNSIGNED | 否 | `0` | 点赞数（冗余计数，明细见 `interactions`） |
| comments_count | INT UNSIGNED | 否 | `0` | 评论数（冗余计数，明细见 `comments`） |
| is_pinned | TINYINT(1) | 否 | `0` | 是否门顶置 |
| created_at | DATETIME(3) | 否 | `CURRENT_TIMESTAMP(3)` | 创建时间 |
| updated_at | DATETIME(3) | 否 | `CURRENT_TIMESTAMP(3)` ON UPDATE | 更新时间，自动刷新 |

**索引**

| 索引名 | 类型 | 列 | 说明 |
| --- | --- | --- | --- |
| PRIMARY | 主键 | id | — |
| idx_list | 普通 | (content_type, state, published_at DESC) | 列表页主查询路径 |
| idx_author | 普通 | (author_id, state) | 「我的文章」按状态筛选 |
| ft_search | FULLTEXT（`WITH PARSER ngram`） | (title, plain_text) | 中文检索，依赖 `ngram_token_size=2` |

#### 4.2.2 media — 图片/文件元数据

> **用途**：图片 / 文件的元数据登记（二进制一律在盘上或对象存储）；**主键**：`id`；**索引**：`uk_storage_key`、`idx_sha256(sha256)`、`idx_content(content_id)`、`idx_state_trashed(state, trashed_at)`；**关联**：`content.id`（content_id）、`users.id`（uploader_id）。

| 字段名 | 类型 | 允许空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 媒体 ID，主键 |
| storage_key | VARCHAR(200) | 否 | — | 存储键，如 `2026/10/ab12….webp`，唯一 |
| thumb_key | VARCHAR(200) | 是 | NULL | 缩略图 key（落盘缓存，不做内存 LRU） |
| kind | VARCHAR(10) | 否 | — | `image` / `file` |
| sha256 | CHAR(64) | 否 | — | 内容哈希，用于去重（同 hash 复用 `storage_key`） |
| original_name | VARCHAR(255) | 否 | — | 原始文件名 |
| mime_type | VARCHAR(100) | 否 | — | MIME 类型 |
| width | INT | 是 | NULL | 像素宽 |
| height | INT | 是 | NULL | 像素高 |
| size_bytes | BIGINT | 否 | — | 字节大小 |
| content_id | BIGINT UNSIGNED | 是 | NULL | 作为图库项 / 封面时的归属，关联 `content.id` |
| uploader_id | BIGINT UNSIGNED | 否 | — | 上传者，关联 `users.id` |
| state | VARCHAR(10) | 否 | `'active'` | `active` / `trash` / `purged` |
| trashed_at | DATETIME(3) | 是 | NULL | 进入回收站时间 |
| created_at | DATETIME(3) | 否 | `CURRENT_TIMESTAMP(3)` | 创建时间 |

**索引**

| 索引名 | 类型 | 列 | 说明 |
| --- | --- | --- | --- |
| PRIMARY | 主键 | id | — |
| uk_storage_key | UNIQUE | storage_key | 存储键唯一 |
| idx_sha256 | 普通 | sha256 | 内容去重查找 |
| idx_content | 普通 | content_id | 按内容查媒体 |
| idx_state_trashed | 普通 | (state, trashed_at) | 回收站清理任务扫描（保留 30 天，`TRASH_RETENTION_DAYS=30`） |

#### 4.2.3 labels — 标签

> **用途**：标签字典；**主键**：`id`；**索引**：`uk_name(name)`；**关联**：经 `content_labels` 关联 `content`。

| 字段名 | 类型 | 允许空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 标签 ID，主键 |
| name | VARCHAR(30) | 否 | — | 标签名，唯一 |

**索引**

| 索引名 | 类型 | 列 | 说明 |
| --- | --- | --- | --- |
| PRIMARY | 主键 | id | — |
| uk_name | UNIQUE | name | 标签名唯一 |

#### 4.2.4 content_labels — 内容-标签关联

> **用途**：内容与标签的多对多关联（替代旧实现 `FIND_IN_SET` 字符串匹配，筛选走索引）；**主键**：`(content_id, label_id)` 联合主键；**索引**：`idx_label(label_id)`；**关联**：`content.id`、`labels.id`。

| 字段名 | 类型 | 允许空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| content_id | BIGINT UNSIGNED | 否 | — | 内容 ID，联合主键之一，关联 `content.id` |
| label_id | BIGINT UNSIGNED | 否 | — | 标签 ID，联合主键之一，关联 `labels.id` |

**索引**

| 索引名 | 类型 | 列 | 说明 |
| --- | --- | --- | --- |
| PRIMARY | 主键（联合） | (content_id, label_id) | 同一内容不会重复挂同一标签 |
| idx_label | 普通 | label_id | 按标签反查内容 |

#### 4.2.5 subsets — 合集

> **用途**：内容合集（专题 / 相册分组）；**主键**：`id`；**索引**：除主键外**无任何索引**；**关联**：`users.id`（owner_id）、`media.id`（cover_media_id）、`content`（经 `subset_items`）。

| 字段名 | 类型 | 允许空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 合集 ID，主键 |
| name | VARCHAR(50) | 否 | — | 合集名称 |
| description | VARCHAR(200) | 是 | NULL | 描述 |
| cover_media_id | BIGINT UNSIGNED | 是 | NULL | 封面图，关联 `media.id` |
| owner_id | BIGINT UNSIGNED | 否 | — | 归属用户，关联 `users.id` |
| created_at | DATETIME(3) | 否 | `CURRENT_TIMESTAMP(3)` | 创建时间 |

**索引**

| 索引名 | 类型 | 列 | 说明 |
| --- | --- | --- | --- |
| PRIMARY | 主键 | id | — |

> ⚠️ `owner_id` / `cover_media_id` **无二级索引**，按归属人或封面查询会走全表扫描（数据量小可接受；详见 §6 待确认）。

#### 4.2.6 subset_items — 合集条目

> **用途**：合集与内容的关联及排序；**主键**：`(subset_id, content_id)` 联合主键；**索引**：仅联合主键；**关联**：`subsets.id`、`content.id`。

| 字段名 | 类型 | 允许空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| subset_id | BIGINT UNSIGNED | 否 | — | 合集 ID，联合主键之一，关联 `subsets.id` |
| content_id | BIGINT UNSIGNED | 否 | — | 内容 ID，联合主键之一，关联 `content.id` |
| sort | INT | 否 | `0` | 排序权重 |

**索引**

| 索引名 | 类型 | 列 | 说明 |
| --- | --- | --- | --- |
| PRIMARY | 主键（联合） | (subset_id, content_id) | 同一内容在同一合集中不重复 |

> ⚠️ 无 `idx_content(content_id)`，按内容反查所属合集需全表扫描（详见 §6 待确认）。

#### 4.2.7 diaries — 日记

> **用途**：个人日记，独立域，不并入 `content`；**主键**：`id`；**索引**：`uk_author_date(author_id, diary_date)`；**关联**：`users.id`（author_id）、`comments` / `interactions`（以 `target_type='diary'` 多态引用）。

| 字段名 | 类型 | 允许空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 日记 ID，主键 |
| author_id | BIGINT UNSIGNED | 否 | — | 作者，关联 `users.id` |
| diary_date | DATE | 否 | — | 日记日期；同一作者一天一篇 |
| weather | VARCHAR(10) | 否 | — | 天气白名单 8 值：晴天 / 多云 / 大雪 / 大雨 / 阵风 / 雷雨 / 雾霾 / 夜晚 |
| mood | VARCHAR(20) | 是 | NULL | 心情（自由短语） |
| body | MEDIUMTEXT | 是 | NULL | 正文，Tiptap JSON |
| state | VARCHAR(10) | 否 | `'draft'` | 与 `content.state` 同口径：`draft` / `published` / `hidden` / `deleted` |
| views | INT UNSIGNED | 否 | `0` | 浏览数（冗余） |
| praises | INT UNSIGNED | 否 | `0` | 点赞数（冗余） |
| created_at | DATETIME(3) | 否 | `CURRENT_TIMESTAMP(3)` | 创建时间 |
| updated_at | DATETIME(3) | 否 | `CURRENT_TIMESTAMP(3)` ON UPDATE | 更新时间，自动刷新 |

**索引**

| 索引名 | 类型 | 列 | 说明 |
| --- | --- | --- | --- |
| PRIMARY | 主键 | id | — |
| uk_author_date | UNIQUE | (author_id, diary_date) | 保证「一天一篇」 |

> ⚠️ `diaries` 没有 `published_at` 列，但 `state` 取值与 `content` 一致包含 `published`，对外列表排序（按发布时间）如何处理**待确认**（详见 §6）。

---

### 4.3 互动域

#### 4.3.1 interactions — 内容/日记点赞

> **用途**：文章与日记的统一点赞记录；**主键**：`id`；**索引**：`uk_target_user(target_type, target_id, user_id)`；**关联**：`users.id`（user_id）、多态引用 `content.id` / `diaries.id`。

| 字段名 | 类型 | 允许空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 点赞记录 ID，主键 |
| target_type | ENUM('content','diary') | 否 | — | 目标类型枚举 |
| target_id | BIGINT UNSIGNED | 否 | — | 目标 ID（按 target_type 决定所属表） |
| user_id | BIGINT UNSIGNED | 否 | — | 点赞用户，关联 `users.id` |
| created_at | DATETIME(3) | 否 | `CURRENT_TIMESTAMP(3)` | 点赞时间 |

**索引**

| 索引名 | 类型 | 列 | 说明 |
| --- | --- | --- | --- |
| PRIMARY | 主键 | id | — |
| uk_target_user | UNIQUE | (target_type, target_id, user_id) | 同一用户对同一目标只能点赞一次 |

#### 4.3.2 comments — 统一评论

> **用途**：文章 / 日记 / 留言墙回复的统一评论表（两级封顶 + 楼中楼指向）；**主键**：`id`；**索引**：`idx_target(target_type, target_id, status, parent_id, created_at)`、`idx_author(author_id)`；**关联**：`users.id`（author_id / reply_to_user_id）、`comments.id`（parent_id / reply_to_comment_id）、多态引用 `content.id` / `diaries.id` / `guestbook.id`。

| 字段名 | 类型 | 允许空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 评论 ID，主键 |
| target_type | VARCHAR(10) | 否 | — | `content` / `diary` / `guestbook` |
| target_id | BIGINT UNSIGNED | 否 | — | 目标 ID（按 target_type 决定所属表） |
| author_id | BIGINT UNSIGNED | 否 | — | 评论作者，关联 `users.id` |
| parent_id | BIGINT UNSIGNED | 是 | NULL | NULL = 一级评论；非 NULL = 所属**一级**评论 id（两级封顶） |
| reply_to_comment_id | BIGINT UNSIGNED | 是 | NULL | 楼中楼指向的「被回复评论」 |
| reply_to_user_id | BIGINT UNSIGNED | 是 | NULL | 冗余被回复人，避免多跳查询 |
| body | VARCHAR(2000) | 否 | — | 纯文本评论（不走富文本） |
| praise_count | INT UNSIGNED | 否 | `0` | 点赞数（冗余，明细见 `comment_praises`） |
| status | VARCHAR(10) | 否 | `'visible'` | `visible` / `deleted`（逻辑删除） |
| created_at | DATETIME(3) | 否 | `CURRENT_TIMESTAMP(3)` | 创建时间 |

**索引**

| 索引名 | 类型 | 列 | 说明 |
| --- | --- | --- | --- |
| PRIMARY | 主键 | id | — |
| idx_target | 普通 | (target_type, target_id, status, parent_id, created_at) | 目标下评论列表查询（按层级 + 时间排序） |
| idx_author | 普通 | author_id | 「我的评论」 |

#### 4.3.3 comment_praises — 评论点赞

> **用途**：评论点赞明细；**主键**：`(comment_id, user_id)` 联合主键；**索引**：仅联合主键；**关联**：`comments.id`、`users.id`。

| 字段名 | 类型 | 允许空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| comment_id | BIGINT UNSIGNED | 否 | — | 评论 ID，联合主键之一，关联 `comments.id` |
| user_id | BIGINT UNSIGNED | 否 | — | 点赞用户，联合主键之一，关联 `users.id` |

**索引**

| 索引名 | 类型 | 列 | 说明 |
| --- | --- | --- | --- |
| PRIMARY | 主键（联合） | (comment_id, user_id) | 同一用户对同一评论只能点赞一次 |

#### 4.3.4 comment_reports — 评论举报

> **用途**：评论举报与后台处理工单；**主键**：`id`；**索引**：`uk_comment_reporter(comment_id, reporter_id)`、`idx_status(status, created_at)`；**关联**：`comments.id`（comment_id）、`users.id`（reporter_id / handled_by）。

| 字段名 | 类型 | 允许空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 举报 ID，主键 |
| comment_id | BIGINT UNSIGNED | 否 | — | 被举报评论，关联 `comments.id` |
| reporter_id | BIGINT UNSIGNED | 否 | — | 举报人，关联 `users.id` |
| reason | VARCHAR(20) | 否 | — | 白名单：`spam` / `abuse` / `porn` / `illegal` / `misinformation` / `other` |
| detail | VARCHAR(200) | 是 | NULL | 补充说明 |
| status | VARCHAR(10) | 否 | `'pending'` | `pending` / `resolved` / `dismissed` |
| handled_by | BIGINT UNSIGNED | 是 | NULL | 处理人，关联 `users.id` |
| handled_at | DATETIME(3) | 是 | NULL | 处理时间 |
| handle_note | VARCHAR(200) | 是 | NULL | 处理备注 |
| created_at | DATETIME(3) | 否 | `CURRENT_TIMESTAMP(3)` | 举报时间 |

**索引**

| 索引名 | 类型 | 列 | 说明 |
| --- | --- | --- | --- |
| PRIMARY | 主键 | id | — |
| uk_comment_reporter | UNIQUE | (comment_id, reporter_id) | 同一用户对同一评论只能举报一次（返回 40900 状态冲突） |
| idx_status | 普通 | (status, created_at) | 后台工作台待处理队列 |

---

### 4.4 私信与留言墙域

#### 4.4.1 conversations — 私信会话

> **用途**：用户两两之间的私信会话；**主键**：`id`；**索引**：`uk_pair(user_a_id, user_b_id)`；**关联**：`users.id`（user_a_id / user_b_id）、`direct_messages.conversation_id`。

| 字段名 | 类型 | 允许空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 会话 ID，主键 |
| user_a_id | BIGINT UNSIGNED | 否 | — | 参与方 A，关联 `users.id`；**约定 a < b** |
| user_b_id | BIGINT UNSIGNED | 否 | — | 参与方 B，关联 `users.id`；**约定 a < b** |
| last_message_at | DATETIME(3) | 是 | NULL | 最近一条消息时间（会话列表排序） |
| a_unread | INT UNSIGNED | 否 | `0` | A 的未读数 |
| b_unread | INT UNSIGNED | 否 | `0` | B 的未读数 |
| created_at | DATETIME(3) | 否 | `CURRENT_TIMESTAMP(3)` | 创建时间 |

**索引**

| 索引名 | 类型 | 列 | 说明 |
| --- | --- | --- | --- |
| PRIMARY | 主键 | id | — |
| uk_pair | UNIQUE | (user_a_id, user_b_id) | 同一对用户只有一个会话（依赖写入方保证 a<b 规范化） |

> ⚠️ `last_message_at` 无索引，且 `uk_pair` 仅支持 (a,b) 顺序查找；查「某用户的全部会话」时若 B 侧为该用户，需 `user_b_id` 过滤——**该列无独立索引**，属潜在性能点（详见 §6）。

#### 4.4.2 direct_messages — 私信

> **用途**：会话内的私信消息体；**主键**：`id`；**索引**：`idx_conv_time(conversation_id, created_at DESC)`；**关联**：`conversations.id`（conversation_id）、`users.id`（sender_id）。

| 字段名 | 类型 | 允许空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 消息 ID，主键 |
| conversation_id | BIGINT UNSIGNED | 否 | — | 所属会话，关联 `conversations.id` |
| sender_id | BIGINT UNSIGNED | 否 | — | 发送者，关联 `users.id` |
| body | VARCHAR(2000) | 否 | — | 消息正文 |
| read_at | DATETIME(3) | 是 | NULL | 已读时间 |
| created_at | DATETIME(3) | 否 | `CURRENT_TIMESTAMP(3)` | 发送时间 |

**索引**

| 索引名 | 类型 | 列 | 说明 |
| --- | --- | --- | --- |
| PRIMARY | 主键 | id | — |
| idx_conv_time | 普通 | (conversation_id, created_at DESC) | 游标分页拉取会话消息 |

#### 4.4.3 guestbook — 留言墙

> **用途**：留言墙的一级留言；**主键**：`id`；**索引**：除主键外**无任何索引**；**关联**：`users.id`（user_id）、`comments`（以 `target_type='guestbook'` 多态引用）。

| 字段名 | 类型 | 允许空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 留言 ID，主键 |
| user_id | BIGINT UNSIGNED | 否 | — | 留言人，关联 `users.id` |
| body | VARCHAR(2000) | 否 | — | 留言正文 |
| status | VARCHAR(10) | 否 | `'visible'` | `visible` / `deleted` |
| created_at | DATETIME(3) | 否 | `CURRENT_TIMESTAMP(3)` | 创建时间 |

**索引**

| 索引名 | 类型 | 列 | 说明 |
| --- | --- | --- | --- |
| PRIMARY | 主键 | id | — |

> 留言墙的**回复走 `comments` 表**（`target_type='guestbook'`，`target_id = guestbook.id`），本表只存一级留言。

---

### 4.5 文件域

#### 4.5.1 files — 后台通用附件

> **用途**：后台文件管理域的通用附件，独立于 `media`（内容域媒体）；**主键**：`id`；**索引**：`uk_storage_key`、`idx_state_trashed(state, trashed_at)`；**关联**：`users.id`（uploader_id）。

| 字段名 | 类型 | 允许空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 附件 ID，主键 |
| uploader_id | BIGINT UNSIGNED | 否 | — | 上传者，关联 `users.id` |
| storage_key | VARCHAR(200) | 否 | — | 存储键，唯一 |
| original_name | VARCHAR(255) | 否 | — | 原始文件名 |
| mime_type | VARCHAR(100) | 否 | — | MIME 类型 |
| size_bytes | BIGINT | 否 | — | 字节大小 |
| sha256 | CHAR(64) | 否 | — | 内容哈希 |
| state | VARCHAR(10) | 否 | `'active'` | `active` / `trash` / `purged` |
| trashed_at | DATETIME(3) | 是 | NULL | 进入回收站时间 |
| created_at | DATETIME(3) | 否 | `CURRENT_TIMESTAMP(3)` | 创建时间 |

**索引**

| 索引名 | 类型 | 列 | 说明 |
| --- | --- | --- | --- |
| PRIMARY | 主键 | id | — |
| uk_storage_key | UNIQUE | storage_key | 存储键唯一 |
| idx_state_trashed | 普通 | (state, trashed_at) | 回收站清理扫描（保留 30 天） |

**配额与保留策略**（应用层配置，`application.yml`）：

| 项 | 值 | 配置项 |
| --- | --- | --- |
| 单文件上传上限 | 20MB | `app.upload-max-bytes` / `UPLOAD_MAX_BYTES`；multipart 上限 `spring.servlet.multipart.max-file-size=20MB`、`max-request-size=25MB` |
| 回收站保留 | 30 天 | `app.trash-retention-days` / `TRASH_RETENTION_DAYS` |
| 备份区上限 | 2GiB（2147483648 字节） | `deploy/.env.example` `BACKUP_QUOTA_BYTES`；用量 ≥80% 记 WARN、≥100% 记 ERROR |
| 备份保留 | 14 天 | `BACKUP_KEEP_DAYS` |

---

### 4.6 系统与审计域

#### 4.6.1 view_logs — 浏览统计

> **用途**：业务浏览量统计与去重（默认 30 分钟窗口）；**主键**：`id`；**索引**：`idx_target_time(target_type, target_id, viewed_at)`、`idx_time(viewed_at)`；**关联**：`users.id`（user_id，可空）、多态引用 `content.id` / `diaries.id`。

| 字段名 | 类型 | 允许空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 记录 ID，主键 |
| target_type | ENUM('content','diary','portal') | 否 | — | 目标类型枚举 |
| target_id | BIGINT UNSIGNED | 是 | NULL | 目标 ID；`portal` 时为 NULL |
| user_id | BIGINT UNSIGNED | 是 | NULL | 登录用户，关联 `users.id` |
| visitor_key | CHAR(64) | 否 | — | 登录用户为 `u:{id}` 的 SHA-256；游客为 SHA-256(IP+UA)——**不落明文 IP** |
| viewed_at | DATETIME(3) | 否 | `CURRENT_TIMESTAMP(3)` | 浏览时间 |

**索引**

| 索引名 | 类型 | 列 | 说明 |
| --- | --- | --- | --- |
| PRIMARY | 主键 | id | — |
| idx_target_time | 普通 | (target_type, target_id, viewed_at) | 按目标统计浏览量 |
| idx_time | 普通 | viewed_at | 按时间范围清理 |

**策略**：

- 去重窗口 30 分钟：`app.view-dedup-minutes` / `VIEW_DEDUP_MINUTES`（`application.yml:54`）。
- 保留期按开发文档为 7 天滚动清理（**清理任务是否已实现待确认**，见 §6）。

#### 4.6.2 request_audits — 请求审计

> **用途**：全站请求级审计（原 Express 实现的 `record` 表），90 天滚动清理；**主键**：`id`；**索引**：`idx_time(created_at)`、`idx_user(user_id)`；**关联**：`users.id`（user_id，可空）。

| 字段名 | 类型 | 允许空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 审计记录 ID，主键 |
| request_id | CHAR(32) | 否 | — | 请求追踪 ID（与响应壳 `requestId` 对应） |
| user_id | BIGINT UNSIGNED | 是 | NULL | 登录用户，关联 `users.id` |
| method | VARCHAR(10) | 否 | — | HTTP 方法 |
| path | VARCHAR(200) | 否 | — | 请求路径 |
| status | INT | 否 | — | HTTP 响应状态码 |
| ip | VARCHAR(45) | 是 | NULL | 客户端 IP（兼容 IPv6） |
| ua | VARCHAR(300) | 是 | NULL | User-Agent 原文 |
| duration_ms | INT | 否 | — | 处理耗时（毫秒） |
| geo_country | VARCHAR(60) | 是 | NULL | 归属国家；**geo 默认关闭**（`GEOIP_ENABLED=false`） |
| geo_city | VARCHAR(60) | 是 | NULL | 归属城市 |
| created_at | DATETIME(3) | 否 | `CURRENT_TIMESTAMP(3)` | 记录时间 |

**索引**

| 索引名 | 类型 | 列 | 说明 |
| --- | --- | --- | --- |
| PRIMARY | 主键 | id | — |
| idx_time | 普通 | created_at | 90 天滚动清理（`AUDIT_RETENTION_DAYS=90`） |
| idx_user | 普通 | user_id | 按用户检索审计 |

**关联的 Java 侧**：`backend/src/main/java/com/linblog/module/audit/entity/RequestAudit.java`（`@TableName("request_audits")`，字段与本表一致）、`.../mapper/RequestAuditMapper.java:14-15`（`deleteOlderThan(before)` 按 `created_at` 滚动清理）。

#### 4.6.3 system_logs — 业务操作日志

> **用途**：后台业务操作留痕（谁、在哪个模块、做了什么）；**主键**：`id`；**索引**：`idx_module_time(module, created_at)`、`idx_user(user_id)`；**关联**：`users.id`（user_id，可空）、泛化引用 `target_type` / `target_id`。

| 字段名 | 类型 | 允许空 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 日志 ID，主键 |
| user_id | BIGINT UNSIGNED | 是 | NULL | 操作人，关联 `users.id` |
| module | VARCHAR(30) | 否 | — | 业务模块名 |
| action | VARCHAR(30) | 否 | — | 操作动作 |
| target_type | VARCHAR(30) | 是 | NULL | 目标对象类型 |
| target_id | BIGINT UNSIGNED | 是 | NULL | 目标对象 ID |
| detail | VARCHAR(500) | 是 | NULL | 细节描述 |
| ip | VARCHAR(45) | 是 | NULL | 操作来源 IP |
| created_at | DATETIME(3) | 否 | `CURRENT_TIMESTAMP(3)` | 记录时间 |

**索引**

| 索引名 | 类型 | 列 | 说明 |
| --- | --- | --- | --- |
| PRIMARY | 主键 | id | — |
| idx_module_time | 普通 | (module, created_at) | 按模块 + 时间检索 |
| idx_user | 普通 | user_id | 按用户检索操作记录 |

**关联的 Java 侧**：`backend/src/main/java/com/linblog/module/audit/entity/SystemLog.java`（`@TableName("system_logs")`，字段与本表一致）、`.../mapper/SystemLogMapper.java`。

---

## 五、维护与调试

### 5.1 本地建库

```sql
-- 库名必须为 springlinblog
CREATE DATABASE springlinblog
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'springlinblog'@'%' IDENTIFIED BY '<password>';
GRANT ALL PRIVILEGES ON springlinblog.* TO 'springlinblog'@'%';
FLUSH PRIVILEGES;
```

应用启动时 Flyway 自动执行 `V1__init.sql` 建齐 22 张表，并在 `springlinblog` 库内创建 `flyway_schema_history` 历史表。

> `baseline-on-migrate: false`：**不要**手工执行 `flyway baseline` 绕过校验，除非明确知道要接管一个已存在的非空库。

### 5.2 常用检查 SQL

```sql
-- 核对表数量（应为 22）
SELECT COUNT(*) FROM information_schema.tables
 WHERE table_schema = 'springlinblog' AND table_type = 'BASE TABLE'
   AND table_name <> 'flyway_schema_history';

-- 核对字符集与排序规则
SELECT table_name, table_collation
  FROM information_schema.tables
 WHERE table_schema = 'springlinblog';

-- 查看全文索引与 ngram 配置
SHOW VARIABLES LIKE 'ngram_token_size';
SHOW INDEX FROM springlinblog.content;

-- 查看 Flyway 迁移历史
SELECT installed_rank, version, description, success
  FROM springlinblog.flyway_schema_history ORDER BY installed_rank;
```

### 5.3 备份与恢复

- 备份脚本：`deploy/backup.sh`，产出 `springlinblog_<timestamp>.sql.gz`，滚动保留 `BACKUP_KEEP_DAYS`（默认 14 天），备份区配额 `BACKUP_QUOTA_BYTES`（默认 2GiB）。
- 备份服务：`deploy/docker-compose.yml:102-127` 的 `backup` 容器，读取 `MYSQL_DATABASE` / `DB_HOST=mysql` / `DB_PORT=3306`。

### 5.4 常见问题排查

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| 启动报 `Unable to obtain connection from database` / `Communications link failure` | MySQL 未就绪或连接串错误 | 确认 `DB_URL` 库名为 `springlinblog`；compose 内主机名须为 `mysql:3306`，本地为 `localhost:3306` |
| Flyway 报「non-empty schema ... baseline」 | 目标库已存在但无 `flyway_schema_history` | 清空 `springlinblog` 库重建，或人工执行基线并补记版本 |
| 中文全文检索无结果 | `ngram_token_size` 不是 2 或索引未重建 | `SHOW VARIABLES LIKE 'ngram_token_size'`；改参数后 drop 并重建 `ft_search` |
| 时间字段相差 8 小时 | JDBC 时区未设置 | 连接串保留 `serverTimezone=Asia/Shanghai` |
| 连接池耗尽 | 长事务 / 慢 SQL | 关注 Hikari `maximum-pool-size=10`；检查未关闭的分页查询与长事务 |

---

## 六、已知差异与待确认

### 6.1 SQL 与 Java 侧差异（以 SQL 为准）

| # | 位置 | 差异描述 | 结论 |
| --- | --- | --- | --- |
| D1 | `V1__init.sql:19` ↔ `backend/src/main/java/com/linblog/module/user/entity/User.java:24-27` | SQL 中 `users.is_super_admin` 为 `STORED` 生成列；Java 实体 `User` **未声明**该字段（仅注释说明「不参与读写」，见 `User.java:9-10`） | **以 SQL 为准**：`is_super_admin` 由数据库根据 `role_code` 自动计算，写操作不得包含该列。Java 侧行为与 SQL 一致，非缺陷。 |
| D2 | `V1__init.sql:18` ↔ `backend/src/main/java/com/linblog/module/permission/PermissionKeys.java:17-22` | SQL `users.role_code` 注释列出 `super_admin/admin/editor/viewer` 四值，默认 `viewer`；`role_permissions` 表仅存**可配置角色**（`admin`/`editor`/`viewer`）的覆盖值，`super_admin` 键集硬编码在代码常量中、不落表 | **以 SQL 为准**：`role_code` 允许四值；`role_permissions` 中出现 `super_admin` 行无意义，接口层应拒绝（`PermissionKeys.CONFIGURABLE_ROLES`）。 |
| D3 | `V1__init.sql:24-27` ↔ `backend/src/main/java/com/linblog/module/user/service/SuperAdminSeeder.java:24-26` | 种子超管的 `username`/`email`/`password` 来自 Java 常量（`admin` / `admin@linlog.com` / 初始密码占位），**V1 迁移脚本不含任何种子行** | **以 SQL 为准**：`users` 表初始为空；超管由 `SuperAdminSeeder`（`ApplicationRunner`）在首次启动时创建。文档不把种子值当作表结构约束。 |
| D4 | `V1__init.sql:41` 与 `V1__init.sql:53` | `refresh_tokens` 与 `password_reset_tokens` 各自定义了**同名**唯一索引 `uk_token_hash` | ⚠️ **可疑点（不阻断执行）**：MySQL 的索引名只需在**表内**唯一，跨表同名合法，因此脚本可正常执行；但运维脚本 / 迁移工具按索引名检索时会产生歧义。**建议**重命名其一（见 6.2 W1）。 |
| D5 | `V1__init.sql:82` | `idx_list (content_type, state, published_at DESC)` 使用了 **MySQL 8.0+ 才支持的降序索引**；MySQL 8.4 支持该语法 | 无差异，仅作版本依赖说明：降序索引要求 MySQL ≥ 8.0。 |
| D6 | `V1__init.sql:143` / `V1__init.sql:142` | `diaries.state` 注释为「同 content」，取值集合包含 `published`，但表中**没有 `published_at` 列** | 差异/缺口（见 6.2 W2）。 |

### 6.2 待确认事项

| # | 位置 | 待确认内容 |
| --- | --- | --- |
| W1 | `V1__init.sql:41`、`V1__init.sql:53` | 两处同名唯一索引 `uk_token_hash` 是否需要改为 `uk_refresh_token_hash` / `uk_reset_token_hash`？当前脚本能否在 MySQL 8.4 上正常执行通过（需实际执行验证）。 |
| W2 | `V1__init.sql:138-151` | `diaries` 无 `published_at` 列，但 `state` 含 `published` 取值。已发布日记的时间语义（用 `created_at` 还是需要补列）待确认。 |
| W3 | `V1__init.sql:122-136` | `subsets.owner_id` / `subsets.cover_media_id` 与 `subset_items.content_id` 均无二级索引，按归属人 / 按内容反查合集会全表扫描。是否需要补索引待确认。 |
| W4 | `V1__init.sql:203-212` | `conversations` 只有 `uk_pair(user_a_id, user_b_id)`，没有 `user_b_id` 单列索引；而「我的会话列表」需同时过滤 a、b 两侧。是否补 `idx_user_b(user_b_id)` 待确认。 |
| W5 | `V1__init.sql:224-230` | `guestbook` 仅有主键索引，无 `status` / `created_at` 索引。留言墙列表查询是否走全表待确认。 |
| W6 | `V1__init.sql:234-243` | 开发文档称 `view_logs` 按 7 天保留清理，但 `application.yml` 中只有 `audit-retention-days=90` / `trash-retention-days=30`，**未看到 view_logs 清理窗口配置项**；清理任务是否存在待确认。 |
| W7 | `application.yml:16-18` | HikariCP 仅配置了 `maximum-pool-size` 与 `minimum-idle`，`connection-timeout` / `max-lifetime` / `leak-detection-threshold` 等均为默认值。是否需要显式声明待确认。 |
| W8 | `deploy/.env.example:18-19`、`deploy/docker-compose.yml:20` | `MYSQL_DATABASE` 与 `DB_URL` 中的库名必须一致（注释已强调）。当前配置均为 `springlinblog`，一致；但 `deploy/backup.sh` 的 dump 文件名也硬编码了 `springlinblog_` 前缀，若未来改库名需同步。 |
| W9 | `V1__init.sql:15` | `password_hash VARCHAR(100)`，注释为 bcrypt cost 12。bcrypt 输出长度约 60 字符，100 足够；但若将来换算法（如 argon2，长度更长）需扩列。 |
| W10 | `V1__init.sql:142` | `weather VARCHAR(10)` 中文白名单 8 值（晴天/多云/大雪/大雨/阵风/雷雨/雾霾/夜晚），最长 2 汉字 = 6 字节，容量充裕；白名单本身在应用层维护，DB 未加 CHECK 约束。 |
| W11 | `V1__init.sql:19` | `is_super_admin` 生成列依赖 `role_code`；MySQL 生成列不能引用 AUTO_INCREMENT 列但可引用普通列，此处合法。若未来把 `role_code` 改为 ENUM，需重新验证索引表达式。 |

---

## 附：文档更新记录

| 版本 | 更新时间 | 更新内容 | 更新人 |
| --- | --- | --- | --- |
| v1.0 | 2026-10-30 | 初始文档，基于 `V1__init.sql` 编写，覆盖 22 张表全量结构、索引、连接与迁移配置 | 开发者 |
