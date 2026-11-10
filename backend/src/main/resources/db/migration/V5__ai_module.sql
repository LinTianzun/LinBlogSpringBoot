-- V5：AI 模块（§AI，自原版 LinBlog ai_* 模型移植；生图 pending/model_health 两表随图片能力延后）
-- 原则与原版一致：AI 只产出建议，落库永远由作者确认后走正常保存流程。

CREATE TABLE ai_model (
    id                  INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name                VARCHAR(64)  NOT NULL COMMENT '显示名称',
    provider            VARCHAR(32)  NOT NULL DEFAULT 'openai' COMMENT '协议类型（openai 兼容）',
    base_url            VARCHAR(255) NOT NULL COMMENT '接口地址',
    api_key_cipher      TEXT         NOT NULL COMMENT '密钥密文（AES-256-GCM，v1:iv:tag:ct）',
    chat_model          VARCHAR(128) NOT NULL DEFAULT '' COMMENT '文本模型名',
    timeout_ms          INT UNSIGNED NOT NULL DEFAULT 20000 COMMENT '单次请求超时',
    enabled             TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    is_default          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否默认模型',
    remark              VARCHAR(255) NULL COMMENT '备注',
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_ai_model_name (name),
    KEY idx_ai_model_default (is_default, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 接入模型';

CREATE TABLE ai_setting (
    id                  TINYINT UNSIGNED NOT NULL PRIMARY KEY,
    quota_enabled       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用次数限制',
    daily_limit         INT UNSIGNED NOT NULL DEFAULT 20 COMMENT '每用户每日次数（0 不限）',
    monthly_limit       INT UNSIGNED NOT NULL DEFAULT 100 COMMENT '每用户每月次数（0 不限）',
    super_admin_unlimited TINYINT(1) NOT NULL DEFAULT 1 COMMENT '超级管理员不限次数',
    chat_model_id       INT UNSIGNED NULL COMMENT '文本任务指定模型（空跟随默认）',
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 策略（单行 id=1）';

INSERT INTO ai_setting (id) VALUES (1);

CREATE TABLE ai_usage_log (
    id                 BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id            BIGINT UNSIGNED NOT NULL COMMENT '调用用户',
    feature            VARCHAR(32)  NOT NULL COMMENT '功能（suggest/report-review/operations/test-run）',
    model_name         VARCHAR(128) NULL COMMENT '使用的模型名',
    status             VARCHAR(16)  NOT NULL DEFAULT 'success' COMMENT 'success/failed/denied',
    detail             VARCHAR(255) NULL COMMENT '备注',
    duration_ms        INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '耗时（毫秒）',
    prompt_tokens      INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '输入 token',
    completion_tokens  INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '输出 token',
    output_chars       INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '输出字符数',
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_ai_usage_user_time (user_id, created_at),
    KEY idx_ai_usage_time (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 调用记录';

CREATE TABLE ai_prompt_template (
    id              INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    template_key    VARCHAR(64)  NOT NULL COMMENT '模板标识（内置模板与 feature_key 相同）',
    feature_key     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '所属功能',
    name            VARCHAR(64)  NOT NULL COMMENT '显示名称',
    description     VARCHAR(255) NULL COMMENT '用途说明',
    content         LONGTEXT     NOT NULL COMMENT '提示词正文（{{变量}} 占位）',
    variables       VARCHAR(500) NULL COMMENT '可用变量说明',
    enabled         TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '停用后回退内置提示词',
    is_default      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '内置默认模板',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_ai_prompt_key (template_key),
    KEY idx_ai_prompt_feature (feature_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 提示词模板';

CREATE TABLE ai_prompt_binding (
    feature_key    VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '功能标识',
    template_key   VARCHAR(64) NOT NULL COMMENT '该功能选用的模板',
    updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 功能选用的提示词模板';
