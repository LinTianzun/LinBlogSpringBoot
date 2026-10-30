package com.linblog.module.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linblog.common.HashUtil;
import com.linblog.module.user.entity.User;
import com.linblog.module.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 超管种子（替代 V1 种子行：bcrypt 哈希不适合写死在 SQL）。
 * 首次启动且无超管时创建 admin / admin@linlog.com / LinBlog@2026。
 */
@Component
public class SuperAdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminSeeder.class);
    static final String SEED_USERNAME = "admin";
    static final String SEED_EMAIL = "admin@linlog.com";
    static final String SEED_PASSWORD = "LinBlog@2026";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public SuperAdminSeeder(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getRoleCode, "super_admin"));
        if (count != null && count > 0) {
            return;
        }
        User admin = new User();
        admin.setUsername(SEED_USERNAME);
        admin.setEmail(SEED_EMAIL);
        admin.setPasswordHash(passwordEncoder.encode(SEED_PASSWORD));
        admin.setNickname("管理员");
        admin.setRoleCode("super_admin");
        admin.setStatus(1);
        admin.setTokenVersion(0);
        try {
            userMapper.insert(admin);
            log.info("已创建种子超管 {}（{}），初始密码见部署说明，请尽快修改", SEED_USERNAME, SEED_EMAIL);
        } catch (DuplicateKeyException e) {
            log.warn("种子超管创建冲突（并发启动？）：{}", e.getMessage());
        }
    }
}
