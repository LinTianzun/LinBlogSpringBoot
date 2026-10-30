package com.linblog.security;

import com.linblog.config.CacheConfig;
import com.linblog.module.user.entity.User;
import com.linblog.module.user.mapper.UserMapper;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * 用户认证状态（users.status + token_version）查询，带 30s Caffeine 短缓存（§7.1）：
 * 禁用 / 改密最迟 30s 对旧 access 生效。
 */
@Component
public class UserAuthStateService {

    public record AuthState(boolean enabled, int tokenVersion, String username, String roleCode) {
    }

    private final UserMapper userMapper;
    private final Cache cache;

    public UserAuthStateService(UserMapper userMapper, CacheManager cacheManager) {
        this.userMapper = userMapper;
        this.cache = cacheManager.getCache(CacheConfig.AUTH_STATUS);
    }

    /** 用户不存在返回 null。 */
    public AuthState load(long uid) {
        AuthState cached = cache.get(uid, AuthState.class);
        if (cached != null) {
            return cached;
        }
        User u = userMapper.selectById(uid);
        if (u == null) {
            return null;
        }
        AuthState state = new AuthState(u.getStatus() != null && u.getStatus() == 1,
                u.getTokenVersion() == null ? 0 : u.getTokenVersion(), u.getUsername(), u.getRoleCode());
        cache.put(uid, state);
        return state;
    }

    public void evict(long uid) {
        cache.evict(uid);
    }
}
