-- M4（§5.2 系统设置）：站点设置键值表；键只允许白名单（见 backend SettingService.LIMITS），
-- 全量预置空值行，前端一次 GET 即可渲染全部表单项。
CREATE TABLE site_settings (
    setting_key   VARCHAR(50) NOT NULL PRIMARY KEY,
    setting_value VARCHAR(500) NOT NULL DEFAULT '' COMMENT '空串表示未设置',
    updated_by    BIGINT UNSIGNED NULL COMMENT '最后修改人 users.id',
    updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '系统设置（白名单键）';

INSERT INTO site_settings (setting_key, setting_value) VALUES
    ('site_title', ''),
    ('site_description', ''),
    ('site_keywords', ''),
    ('icp', ''),
    ('footer_text', ''),
    ('announcement', '');
