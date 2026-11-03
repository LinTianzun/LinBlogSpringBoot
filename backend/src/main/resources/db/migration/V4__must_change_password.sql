-- P3 合规（§5 遗留项）：超管初始密码强制首次修改。
-- 种子超管由 SuperAdminSeeder 创建（V1 不再内联种子行，bcrypt 哈希不适合写死在 SQL），
-- 该行置 must_change_password=1：登录后前端守卫强制跳转改密页，改密成功清位。
ALTER TABLE users
    ADD COLUMN must_change_password TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1=必须先修改密码（初始密码场景）';
