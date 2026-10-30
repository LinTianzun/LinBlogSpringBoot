-- =====================================================================
-- LinBlog V1 初始结构（开发文档 §3）：22 张表一次建齐
-- 约定：主键 BIGINT UNSIGNED AUTO_INCREMENT；时间 DATETIME(3)；
--       字符集 utf8mb4 / utf8mb4_0900_ai_ci；
--       所有外键只建索引不建物理外键（便于归档与迁移）；
--       软删用状态列，不用 deleted_at（与原项目口径一致）。
-- =====================================================================

-- 3.1 用户与认证 ---------------------------------------------------------

CREATE TABLE users (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(50)  NOT NULL COMMENT '登录名 ^[a-zA-Z0-9_]{3,20}$',
    email           VARCHAR(100) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL COMMENT 'bcrypt cost 12',
    nickname        VARCHAR(50)  NULL COMMENT '展示名',
    avatar_media_id BIGINT UNSIGNED NULL COMMENT '关联 media',
    role_code       VARCHAR(20)  NOT NULL DEFAULT 'viewer' COMMENT 'super_admin/admin/editor/viewer',
    is_super_admin  TINYINT(1) GENERATED ALWAYS AS (CASE WHEN role_code = 'super_admin' THEN 1 ELSE 0 END) STORED COMMENT '生成列',
    status          TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1 正常 / 0 禁用',
    token_version   INT          NOT NULL DEFAULT 0 COMMENT '改密/重置 +1，旧 access 全失效',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_email (email),
    -- MySQL 8 函数索引：非超管表达式为 NULL，唯一约束允许多 NULL → 数据库层保证全库唯一超管
    UNIQUE KEY uk_single_super_admin ((CASE WHEN is_super_admin = 1 THEN 1 END))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户';

CREATE TABLE refresh_tokens (
    id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id        BIGINT UNSIGNED NOT NULL,
    family_id      CHAR(36) NOT NULL COMMENT 'UUID；同一登录会话一族',
    token_hash     CHAR(64) NOT NULL COMMENT 'SHA-256；明文只在 Cookie',
    expires_at     DATETIME(3) NOT NULL COMMENT '默认 7 天',
    revoked_at     DATETIME(3) NULL COMMENT 'NULL=有效',
    rotated_to_id  BIGINT UNSIGNED NULL COMMENT '轮换指向的新 token',
    created_ip     VARCHAR(45) NULL,
    created_ua_hash CHAR(32) NULL COMMENT 'SHA-256(UA) hex',
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_token_hash (token_hash),
    KEY idx_user (user_id, expires_at),
    KEY idx_family (family_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '刷新令牌（轮换+复用检测）';

CREATE TABLE password_reset_tokens (
    id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT UNSIGNED NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at DATETIME(3) NOT NULL COMMENT '30 分钟',
    used_at    DATETIME(3) NULL COMMENT '一次性，用后即写',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_token_hash (token_hash),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '密码重置令牌';

CREATE TABLE role_permissions (
    role_code VARCHAR(20) NOT NULL COMMENT '联合 PK',
    perm_key  VARCHAR(40) NOT NULL COMMENT '联合 PK，如 article.create',
    PRIMARY KEY (role_code, perm_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '权限矩阵持久层（覆盖值）';

-- 3.2 内容域 -------------------------------------------------------------

CREATE TABLE content (
    id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    author_id      BIGINT UNSIGNED NOT NULL,
    content_type   VARCHAR(10) NOT NULL COMMENT 'article / image',
    title          VARCHAR(150) NOT NULL COMMENT 'article 必填；image 存图注标题',
    introduce      VARCHAR(512) NULL COMMENT '摘要',
    body           MEDIUMTEXT NULL COMMENT 'Tiptap JSON（image 类型为 NULL）',
    plain_text     MEDIUMTEXT NULL COMMENT '服务端从 JSON 提取的纯文本，仅供检索',
    cover_media_id BIGINT UNSIGNED NULL,
    state          VARCHAR(10) NOT NULL DEFAULT 'draft' COMMENT 'draft/published/hidden/deleted',
    published_at   DATETIME(3) NULL COMMENT '发布时写入',
    views          INT UNSIGNED NOT NULL DEFAULT 0,
    praises        INT UNSIGNED NOT NULL DEFAULT 0,
    comments_count INT UNSIGNED NOT NULL DEFAULT 0,
    is_pinned      TINYINT(1) NOT NULL DEFAULT 0 COMMENT '门顶置',
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_list (content_type, state, published_at DESC),
    KEY idx_author (author_id, state),
    FULLTEXT KEY ft_search (title, plain_text) WITH PARSER ngram
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文章+图库统一内容';

CREATE TABLE media (
    id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    storage_key   VARCHAR(200) NOT NULL COMMENT '如 2026/10/ab12….webp',
    thumb_key     VARCHAR(200) NULL COMMENT '缩略图 key（落盘缓存）',
    kind          VARCHAR(10) NOT NULL COMMENT 'image / file',
    sha256        CHAR(64) NOT NULL COMMENT '内容去重（同 hash 复用 storage_key）',
    original_name VARCHAR(255) NOT NULL,
    mime_type     VARCHAR(100) NOT NULL,
    width         INT NULL,
    height        INT NULL,
    size_bytes    BIGINT NOT NULL,
    content_id    BIGINT UNSIGNED NULL COMMENT '作为图库项/封面时的归属',
    uploader_id   BIGINT UNSIGNED NOT NULL,
    state         VARCHAR(10) NOT NULL DEFAULT 'active' COMMENT 'active/trash/purged',
    trashed_at    DATETIME(3) NULL,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_storage_key (storage_key),
    KEY idx_sha256 (sha256),
    KEY idx_content (content_id),
    KEY idx_state_trashed (state, trashed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '图片/文件元数据';

CREATE TABLE labels (
    id   BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(30) NOT NULL,
    UNIQUE KEY uk_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '标签';

CREATE TABLE content_labels (
    content_id BIGINT UNSIGNED NOT NULL,
    label_id   BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (content_id, label_id),
    KEY idx_label (label_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '内容-标签关联';

CREATE TABLE subsets (
    id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(50) NOT NULL,
    description    VARCHAR(200) NULL,
    cover_media_id BIGINT UNSIGNED NULL,
    owner_id       BIGINT UNSIGNED NOT NULL,
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '合集';

CREATE TABLE subset_items (
    subset_id  BIGINT UNSIGNED NOT NULL,
    content_id BIGINT UNSIGNED NOT NULL,
    sort       INT NOT NULL DEFAULT 0,
    PRIMARY KEY (subset_id, content_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '合集条目';

CREATE TABLE diaries (
    id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    author_id  BIGINT UNSIGNED NOT NULL,
    diary_date DATE NOT NULL COMMENT 'UNIQUE(author_id, diary_date) 一天一篇',
    weather    VARCHAR(10) NOT NULL COMMENT '白名单 8 值：晴天/多云/大雪/大雨/阵风/雷雨/雾霾/夜晚',
    mood       VARCHAR(20) NULL COMMENT '自由短语',
    body       MEDIUMTEXT NULL COMMENT 'Tiptap JSON',
    state      VARCHAR(10) NOT NULL DEFAULT 'draft' COMMENT '同 content',
    views      INT UNSIGNED NOT NULL DEFAULT 0,
    praises    INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_author_date (author_id, diary_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '日记';

CREATE TABLE interactions (
    id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    target_type ENUM('content','diary') NOT NULL,
    target_id   BIGINT UNSIGNED NOT NULL,
    user_id     BIGINT UNSIGNED NOT NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_target_user (target_type, target_id, user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '内容/日记点赞';

-- 3.3 评论域 -------------------------------------------------------------

CREATE TABLE comments (
    id                  BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    target_type         VARCHAR(10) NOT NULL COMMENT 'content / diary / guestbook',
    target_id           BIGINT UNSIGNED NOT NULL,
    author_id           BIGINT UNSIGNED NOT NULL,
    parent_id           BIGINT UNSIGNED NULL COMMENT 'NULL=一级；非 NULL=所属一级评论 id（两级封顶）',
    reply_to_comment_id BIGINT UNSIGNED NULL COMMENT '楼中楼指向的被回复评论',
    reply_to_user_id    BIGINT UNSIGNED NULL COMMENT '冗余被回复人，避免多跳查询',
    body                VARCHAR(2000) NOT NULL COMMENT '纯文本评论',
    praise_count        INT UNSIGNED NOT NULL DEFAULT 0,
    status              VARCHAR(10) NOT NULL DEFAULT 'visible' COMMENT 'visible/deleted',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_target (target_type, target_id, status, parent_id, created_at),
    KEY idx_author (author_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '统一评论';

CREATE TABLE comment_praises (
    comment_id BIGINT UNSIGNED NOT NULL,
    user_id    BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (comment_id, user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '评论点赞';

CREATE TABLE comment_reports (
    id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    comment_id   BIGINT UNSIGNED NOT NULL,
    reporter_id  BIGINT UNSIGNED NOT NULL,
    reason       VARCHAR(20) NOT NULL COMMENT 'spam/abuse/porn/illegal/misinformation/other',
    detail       VARCHAR(200) NULL COMMENT '补充说明',
    status       VARCHAR(10) NOT NULL DEFAULT 'pending' COMMENT 'pending/resolved/dismissed',
    handled_by   BIGINT UNSIGNED NULL,
    handled_at   DATETIME(3) NULL,
    handle_note  VARCHAR(200) NULL,
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_comment_reporter (comment_id, reporter_id),
    KEY idx_status (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '评论举报';

-- 3.4 私信与留言墙 ---------------------------------------------------------

CREATE TABLE conversations (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_a_id       BIGINT UNSIGNED NOT NULL COMMENT '约定 a<b',
    user_b_id       BIGINT UNSIGNED NOT NULL,
    last_message_at DATETIME(3) NULL,
    a_unread        INT UNSIGNED NOT NULL DEFAULT 0,
    b_unread        INT UNSIGNED NOT NULL DEFAULT 0,
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_pair (user_a_id, user_b_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '私信会话';

CREATE TABLE direct_messages (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT UNSIGNED NOT NULL,
    sender_id       BIGINT UNSIGNED NOT NULL,
    body            VARCHAR(2000) NOT NULL,
    read_at         DATETIME(3) NULL,
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_conv_time (conversation_id, created_at DESC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '私信';

CREATE TABLE guestbook (
    id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT UNSIGNED NOT NULL,
    body       VARCHAR(2000) NOT NULL,
    status     VARCHAR(10) NOT NULL DEFAULT 'visible' COMMENT 'visible/deleted',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '留言墙一级留言（回复走 comments）';

-- 3.5 统计与审计 ---------------------------------------------------------

CREATE TABLE view_logs (
    id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    target_type ENUM('content','diary','portal') NOT NULL,
    target_id   BIGINT UNSIGNED NULL COMMENT 'portal 时为 NULL',
    user_id     BIGINT UNSIGNED NULL,
    visitor_key CHAR(64) NOT NULL COMMENT '登录 u:{id} 的 SHA-256；游客 SHA-256(IP+UA)，不落明文 IP',
    viewed_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_target_time (target_type, target_id, viewed_at),
    KEY idx_time (viewed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '业务浏览统计（30 分钟去重）';

CREATE TABLE request_audits (
    id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    request_id  CHAR(32) NOT NULL,
    user_id     BIGINT UNSIGNED NULL,
    method      VARCHAR(10) NOT NULL,
    path        VARCHAR(200) NOT NULL,
    status      INT NOT NULL,
    ip          VARCHAR(45) NULL,
    ua          VARCHAR(300) NULL,
    duration_ms INT NOT NULL,
    geo_country VARCHAR(60) NULL COMMENT 'geo 默认关闭（GEOIP_ENABLED=false）',
    geo_city    VARCHAR(60) NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_time (created_at),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '请求审计（原 record 表，90 天滚动清理）';

CREATE TABLE system_logs (
    id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT UNSIGNED NULL,
    module      VARCHAR(30) NOT NULL,
    action      VARCHAR(30) NOT NULL,
    target_type VARCHAR(30) NULL,
    target_id   BIGINT UNSIGNED NULL,
    detail      VARCHAR(500) NULL,
    ip          VARCHAR(45) NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_module_time (module, created_at),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '业务操作日志';

CREATE TABLE files (
    id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    uploader_id   BIGINT UNSIGNED NOT NULL,
    storage_key   VARCHAR(200) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    mime_type     VARCHAR(100) NOT NULL,
    size_bytes    BIGINT NOT NULL,
    sha256        CHAR(64) NOT NULL,
    state         VARCHAR(10) NOT NULL DEFAULT 'active' COMMENT 'active/trash/purged',
    trashed_at    DATETIME(3) NULL,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_storage_key (storage_key),
    KEY idx_state_trashed (state, trashed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '后台通用附件（单文件 ≤20MB、备份区 2GiB、trash 30 天）';
