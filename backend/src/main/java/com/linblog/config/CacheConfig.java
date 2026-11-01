package com.linblog.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * Caffeine 缓存（§7）：两个命名缓存。
 * - permMatrix：权限矩阵（角色 → 键集合），PUT matrix 保存后 invalidateAll；
 * - authStatus：用户认证状态（status + token_version），30s 短缓存，禁用/改密最迟 30s 生效。
 */
@Configuration
public class CacheConfig {

    public static final String PERM_MATRIX = "permMatrix";
    public static final String AUTH_STATUS = "authStatus";

    @Bean
    public CacheManager cacheManager() {
        Cache<Object, Object> permMatrix = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumSize(200)
                .build();
        Cache<Object, Object> authStatus = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(30))
                .maximumSize(2_000)
                .build();
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                new CaffeineCache(PERM_MATRIX, permMatrix),
                new CaffeineCache(AUTH_STATUS, authStatus)));
        return manager;
    }
}
