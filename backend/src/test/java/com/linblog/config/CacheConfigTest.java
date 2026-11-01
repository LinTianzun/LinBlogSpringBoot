package com.linblog.config;

import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class CacheConfigTest {

    private org.springframework.cache.support.SimpleCacheManager newManager() {
        var manager = (org.springframework.cache.support.SimpleCacheManager) new CacheConfig().cacheManager();
        manager.afterPropertiesSet(); // 容器生命周期外需手动初始化 cacheMap
        return manager;
    }

    @Test
    void exposesTwoNamedCaches() {
        CacheManager manager = newManager();
        var perm = manager.getCache(CacheConfig.PERM_MATRIX);
        var auth = manager.getCache(CacheConfig.AUTH_STATUS);
        assertNotNull(perm);
        assertNotNull(auth);
        assertSame(CaffeineCache.class, perm.getClass());
    }

    @Test
    void cachesPutGetEvict() {
        CacheManager manager = newManager();
        var cache = manager.getCache(CacheConfig.PERM_MATRIX);
        cache.put("k", "v");
        assertEquals("v", cache.get("k", String.class));
        cache.evict("k");
        assertNull(cache.get("k"));
        cache.clear();
        assertNull(manager.getCache(CacheConfig.AUTH_STATUS).get("x"));
    }
}
