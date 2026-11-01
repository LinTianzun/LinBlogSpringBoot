package com.linblog.module.permission.service;

import com.linblog.config.CacheConfig;
import com.linblog.module.permission.PermissionKeys;
import com.linblog.module.permission.mapper.RolePermissionMapper;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.common.BizException;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 权限服务：默认矩阵、覆盖加载、缓存、矩阵保存校验。 */
class PermissionServiceTest {

    private RolePermissionMapper rolePermissionMapper;
    private SystemLogService systemLogService;
    private PermissionService service;

    @BeforeEach
    void setUp() {
        rolePermissionMapper = mock(RolePermissionMapper.class);
        systemLogService = mock(SystemLogService.class);
        var manager = (org.springframework.cache.support.SimpleCacheManager) new CacheConfig().cacheManager();
        manager.afterPropertiesSet(); // 容器生命周期外需手动初始化
        service = new PermissionService(rolePermissionMapper, manager, systemLogService);
    }

    private AuthPrincipal superAdmin() {
        return new AuthPrincipal(1L, "root", "super_admin", true, PermissionKeys.ALL_KEYS);
    }

    private AuthPrincipal editor() {
        return new AuthPrincipal(2L, "ed", "editor", false, PermissionKeys.defaultKeysFor("editor"));
    }

    @Test
    void superAdminHardcodedAllKeys() {
        assertEquals(PermissionKeys.ALL_KEYS, service.keysForRole("super_admin"));
        verify(rolePermissionMapper, never()).selectKeysByRole(anyString());
    }

    @Test
    void unknownOrBlankRoleEmptySet() {
        assertTrue(service.keysForRole("ghost").isEmpty());
        assertTrue(service.keysForRole("").isEmpty());
        assertTrue(service.keysForRole(null).isEmpty());
    }

    @Test
    void noRowsFallsBackToDefaultMatrix() {
        when(rolePermissionMapper.selectKeysByRole("editor")).thenReturn(List.of());
        assertEquals(PermissionKeys.defaultKeysFor("editor"), service.keysForRole("editor"));
    }

    @Test
    void dbRowsOverrideDefaultAndFilterUnknown() {
        when(rolePermissionMapper.selectKeysByRole("editor")).thenReturn(
                List.of("article.viewAll", "not.a.key", PermissionKeys.PERMISSION_EDIT));
        Set<String> keys = service.keysForRole("editor");
        assertEquals(Set.of("article.viewAll", PermissionKeys.PERMISSION_EDIT), keys);
    }

    @Test
    void cachedSecondCallDoesNotHitMapper() {
        when(rolePermissionMapper.selectKeysByRole("viewer")).thenReturn(List.of());
        service.keysForRole("viewer");
        service.keysForRole("viewer");
        verify(rolePermissionMapper, times(1)).selectKeysByRole("viewer");
    }

    @Test
    void invalidateAllForcesReload() {
        when(rolePermissionMapper.selectKeysByRole("viewer")).thenReturn(List.of());
        service.keysForRole("viewer");
        service.invalidateAll();
        service.keysForRole("viewer");
        verify(rolePermissionMapper, times(2)).selectKeysByRole("viewer");
    }

    @Test
    void requireThrows403WhenMissing() {
        BizException ex = assertThrows(BizException.class,
                () -> service.require(editor(), PermissionKeys.PERMISSION_EDIT));
        assertEquals(40300, ex.getErrorCode().code());
        service.require(editor(), PermissionKeys.ARTICLE_CREATE); // 持有键不抛
        service.require(superAdmin(), PermissionKeys.PERMISSION_EDIT);
    }

    @Test
    void requireNullPrincipalThrows() {
        assertThrows(BizException.class, () -> service.require(null, PermissionKeys.OVERVIEW_VIEW));
    }

    @Test
    void saveMatrixRejectsNonSuperAdmin() {
        BizException ex = assertThrows(BizException.class,
                () -> service.saveMatrix(editor(), Map.of("viewer", List.of()), "1.1.1.1"));
        assertEquals(40300, ex.getErrorCode().code());
    }

    @Test
    void saveMatrixRejectsEmptyMatrix() {
        BizException ex = assertThrows(BizException.class,
                () -> service.saveMatrix(superAdmin(), Map.of(), "1.1.1.1"));
        assertEquals(40000, ex.getErrorCode().code());
        assertThrows(BizException.class, () -> service.saveMatrix(superAdmin(), null, "1.1.1.1"));
    }

    @Test
    void saveMatrixRejectsUnknownRoleAndSuperAdminRow() {
        BizException unknown = assertThrows(BizException.class, () -> service.saveMatrix(
                superAdmin(), Map.of("root", List.of("overview.view")), "ip"));
        assertTrue(unknown.getMessage().contains("未知角色"));

        BizException superRow = assertThrows(BizException.class, () -> service.saveMatrix(
                superAdmin(), Map.of("super_admin", List.of("overview.view")), "ip"));
        assertTrue(superRow.getMessage().contains("super_admin"));
    }

    @Test
    void saveMatrixRejectsUnknownAndProtectedKeys() {
        BizException unknown = assertThrows(BizException.class, () -> service.saveMatrix(
                superAdmin(), Map.of("viewer", List.of("no.such.key")), "ip"));
        assertTrue(unknown.getMessage().contains("未知权限键"));

        BizException protectedKey = assertThrows(BizException.class, () -> service.saveMatrix(
                superAdmin(), Map.of("admin", List.of(PermissionKeys.USER_ASSIGN_ADMIN)), "ip"));
        assertTrue(protectedKey.getMessage().contains("受保护"));
        verify(rolePermissionMapper, never()).deleteByRole(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveMatrixSuccessWritesRowsInvalidatesCacheAndLogs() {
        when(rolePermissionMapper.selectKeysByRole("viewer")).thenReturn(List.of());
        service.keysForRole("viewer"); // 填充缓存

        Map<String, Integer> counts = service.saveMatrix(superAdmin(),
                Map.of("viewer", List.of("overview.view", "overview.view", "article.viewAll")),
                "2.2.2.2");

        assertEquals(2, counts.get("viewer"), "重复键去重");
        verify(rolePermissionMapper).deleteByRole("viewer");
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(rolePermissionMapper, times(2)).insert(eq("viewer"), keyCaptor.capture());
        assertTrue(keyCaptor.getAllValues().containsAll(List.of("overview.view", "article.viewAll")));

        // 缓存已失效：再次读取会回到 DB
        service.keysForRole("viewer");
        verify(rolePermissionMapper, times(2)).selectKeysByRole("viewer");

        verify(systemLogService).record(eq(1L), eq("permission"), eq("matrix_update"),
                isNull(), isNull(), anyString(), eq("2.2.2.2"));
    }
}
