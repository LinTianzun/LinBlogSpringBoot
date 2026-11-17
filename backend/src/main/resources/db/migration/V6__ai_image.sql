-- V6：AI 生图（封面/配图，§AI 图片域，自原版 ai_pending_image / ai_model 图片列移植）
-- 候选图暂存在库里而非进程内存：多实例部署时任意实例都能取到同一份候选。

CREATE TABLE ai_pending_image (
    id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    token       CHAR(48)     NOT NULL COMMENT '一次性确认令牌（SecureRandom hex）',
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '生成者（确认时校验归属，他人令牌不可消费）',
    feature     VARCHAR(32)  NOT NULL DEFAULT 'cover' COMMENT '来源功能（cover/image）',
    mime_type   VARCHAR(32)  NOT NULL COMMENT '图片 MIME 类型',
    size        INT UNSIGNED NOT NULL COMMENT '图片大小（字节）',
    data        MEDIUMBLOB   NOT NULL COMMENT '图片二进制内容',
    expires_at  DATETIME(3)  NOT NULL COMMENT '过期时间（TTL 10 分钟）',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_ai_pending_token (token),
    KEY idx_ai_pending_expires (expires_at),
    KEY idx_ai_pending_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 待确认图片暂存';

ALTER TABLE ai_model
    ADD COLUMN image_model VARCHAR(128) NOT NULL DEFAULT '' COMMENT '图片模型名（可空=不出图）' AFTER chat_model,
    ADD COLUMN image_size  VARCHAR(16)  NOT NULL DEFAULT '1344x768' COMMENT '图片尺寸' AFTER image_model;

ALTER TABLE ai_setting
    ADD COLUMN image_model_id  INT UNSIGNED NULL COMMENT '配图任务指定模型（空跟随默认）' AFTER chat_model_id,
    ADD COLUMN remove_watermark TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '成图右下角 AI 生成标识裁除' AFTER image_model_id,
    ADD COLUMN watermark_mode   VARCHAR(16) NOT NULL DEFAULT 'crop' COMMENT '去除方式：crop / crop-resize' AFTER remove_watermark;
