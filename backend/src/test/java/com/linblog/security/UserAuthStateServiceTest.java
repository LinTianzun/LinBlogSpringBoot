package com.linblog.security;

import com.linblog.config.CacheConfig;
import com.linblog.module.user.entity.User;
import com.linblog.module.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** §7.1 认证状态查询：status/token_version 映射、30s 短缓存命中与失效。 */
class UserAuthStateServiceTest {

    private UserMapper userMapper;
    private Cache cache;
    private UserAuthStateService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        CacheManager cacheManager = mock(CacheManager.class);
        cache = mock(Cache.class);
        when(cacheManager.getCache(CacheConfig.AUTH_STATUS)).thenReturn(cache);
        service = new UserAuthStateService(userMapper, cacheManager);
    }

    private User user(Integer status, Integer tokenVersion) {
        User u = new User();
        u.setId(7L);
        u.setUsername("alice");
        u.setRoleCode("editor");
        u.setStatus(status);
        u.setTokenVersion(tokenVersion);
        return u;
    }

    @Test
    void enabledUserMapsToAuthStateAndPopulatesCache() {
        when(userMapper.selectById(7L)).thenReturn(user(1, 2));

        UserAuthStateService.AuthState state = service.load(7L);

        assertTrue(state.enabled());
        assertEquals(2, state.tokenVersion());
        assertEquals("alice", state.username());
        assertEquals("editor", state.roleCode());

        ArgumentCaptor<UserAuthStateService.AuthState> captor =
                ArgumentCaptor.forClass(UserAuthStateService.AuthState.class);
        verify(cache).put(eq(7L), captor.capture());
        assertSame(state, captor.getValue());
    }

    @Test
    void secondLoadHitsCacheWithoutDbCall() {
        UserAuthStateService.AuthState cached =
                new UserAuthStateService.AuthState(true, 3, "bob", "admin");
        when(cache.get(7L, UserAuthStateService.AuthState.class)).thenReturn(cached);

        assertSame(cached, service.load(7L));
        verify(userMapper, never()).selectById(any());
    }

    @Test
    void missingUserReturnsNullAndCachesNothing() {
        when(userMapper.selectById(99L)).thenReturn(null);

        assertNull(service.load(99L));
        verify(cache, never()).put(any(), any());
    }

    @Test
    void disabledStatusMapsToDisabled() {
        when(userMapper.selectById(7L)).thenReturn(user(0, 0));
        assertFalse(service.load(7L).enabled());
    }

    @Test
    void nullStatusMapsToDisabled() {
        when(userMapper.selectById(7L)).thenReturn(user(null, null));

        UserAuthStateService.AuthState state = service.load(7L);
        assertFalse(state.enabled(), "status=null 视为禁用");
        assertEquals(0, state.tokenVersion(), "tokenVersion=null 视为 0");
    }

    @Test
    void evictDelegatesToCache() {
        service.evict(7L);
        verify(cache, times(1)).evict(7L);
    }
}
