package com.linblog.module.user.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.auth.service.AuthService;
import com.linblog.module.user.entity.User;
import com.linblog.module.user.mapper.UserMapper;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** §5.3 管理端用户服务：scope 校验在控制器层，这里覆盖状态机/超管唯一/审计。 */
class AdminUserServiceTest {

    private UserMapper userMapper;
    private AuthService authService;
    private SystemLogService systemLogService;
    private AdminUserService service;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

    private static final AuthPrincipal SUPER =
            new AuthPrincipal(1L, "root", "super_admin", true, java.util.Set.of("user.update"));

    private static User user(long id, String username, String role, int status, int tv) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setEmail(username + "@linlog.com");
        u.setPasswordHash("h");
        u.setNickname(username);
        u.setRoleCode(role);
        u.setStatus(status);
        u.setTokenVersion(tv);
        return u;
    }

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        authService = mock(AuthService.class);
        systemLogService = mock(SystemLogService.class);
        service = new AdminUserService(userMapper, authService, systemLogService, encoder);
    }

    @Test
    void pageMapsRecordsAndTotal() {
        Page<User> page = new Page<>(1, 10);
        page.setRecords(List.of(user(1L, "a", "viewer", 1, 0), user(2L, "b", "editor", 1, 0)));
        page.setTotal(2);
        when(userMapper.selectPage(any(), any())).thenReturn(page);

        PageQuery query = new PageQuery();
        query.setKeyword("  a  ");
        PageResult<AdminUserService.AdminUserView> result = service.page(query, 1);

        assertEquals(2, result.records().size());
        assertEquals(2, result.total());
        assertEquals("a", result.records().get(0).username());
    }

    @Test
    void createSuccessHashesPasswordAndLogs() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        doAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(77L);
            return 1;
        }).when(userMapper).insert(any(User.class));

        AdminUserService.AdminUserView view = service.create("newbie", "n@linlog.com",
                "Password8", null, SUPER, "ip");

        assertEquals("newbie", view.username());
        assertEquals("viewer", view.role(), "新建用户默认 viewer");
        verify(userMapper).insert(any(User.class));
        verify(systemLogService).record(eq(1L), eq("user"), eq("create"), eq("user"), eq(77L),
                eq(null), eq("ip"));
    }

    @Test
    void createConflicts() {
        when(userMapper.selectCount(any())).thenReturn(1L);
        BizException ex = assertThrows(BizException.class,
                () -> service.create("taken", "e@x.c", "Password8", null, SUPER, "ip"));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());

        when(userMapper.selectCount(any())).thenReturn(0L);
        doThrow(new DuplicateKeyException("dup")).when(userMapper).insert(any(User.class));
        assertThrows(BizException.class,
                () -> service.create("racer", "r@x.c", "Password8", null, SUPER, "ip"));
    }

    @Test
    void updateConflictAndSuccess() {
        User existing = user(5L, "bob", "viewer", 1, 0);
        existing.setEmail("bob@linlog.com");
        when(userMapper.selectById(5L)).thenReturn(existing);

        when(userMapper.selectCount(any())).thenReturn(1L);
        BizException conflict = assertThrows(BizException.class,
                () -> service.update(5L, "nick", "taken@linlog.com", SUPER, "ip"));
        assertEquals(ErrorCode.CONFLICT, conflict.getErrorCode());

        when(userMapper.selectCount(any())).thenReturn(0L);
        AdminUserService.AdminUserView view = service.update(5L, "新昵称", "bob2@linlog.com", SUPER, "ip");
        assertEquals("新昵称", view.nickname());
        assertEquals("bob2@linlog.com", view.email());
        verify(userMapper).updateById(existing);
    }

    @Test
    void updateUnknownUser404() {
        when(userMapper.selectById(9L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class,
                () -> service.update(9L, "n", null, SUPER, "ip"));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void disableRevokesTokensButSuperAdminUndisableable() {
        User target = user(5L, "bob", "editor", 1, 0);
        when(userMapper.selectById(5L)).thenReturn(target);
        service.updateStatus(5L, 0, SUPER, "ip");
        assertEquals(0, target.getStatus());
        verify(authService).invalidateUserAuth(5L);
        verify(systemLogService).record(eq(1L), eq("user"), eq("disable"), eq("user"), eq(5L),
                eq(null), eq("ip"));

        User root = user(1L, "root", "super_admin", 1, 0);
        when(userMapper.selectById(1L)).thenReturn(root);
        BizException ex = assertThrows(BizException.class,
                () -> service.updateStatus(1L, 0, SUPER, "ip"));
        assertTrue(ex.getMessage().contains("不可禁用"));
    }

    @Test
    void enableDoesNotRevoke() {
        User target = user(5L, "bob", "viewer", 0, 0);
        when(userMapper.selectById(5L)).thenReturn(target);
        service.updateStatus(5L, 1, SUPER, "ip");
        verify(authService, never()).invalidateUserAuth(5L);
    }

    @Test
    void invalidStatusRejected() {
        when(userMapper.selectById(5L)).thenReturn(user(5L, "bob", "viewer", 1, 0));
        assertThrows(BizException.class, () -> service.updateStatus(5L, 2, SUPER, "ip"));
    }

    @Test
    void resetPasswordBumpsTvAndRevokes() {
        User target = user(5L, "bob", "viewer", 1, 2);
        when(userMapper.selectById(5L)).thenReturn(target);
        service.resetPassword(5L, "FreshPass1", SUPER, "ip");
        assertEquals(3, target.getTokenVersion());
        verify(authService).invalidateUserAuth(5L);
        verify(systemLogService).record(eq(1L), eq("user"), eq("reset_password"), eq("user"), eq(5L),
                eq(null), eq("ip"));
    }

    @Test
    void updateRoleRules() {
        // 未知角色
        when(userMapper.selectById(5L)).thenReturn(user(5L, "bob", "viewer", 1, 0));
        assertThrows(BizException.class, () -> service.updateRole(5L, "chief", SUPER, "ip"));

        // 超管不可降级
        when(userMapper.selectById(1L)).thenReturn(user(1L, "root", "super_admin", 1, 0));
        BizException downgrade = assertThrows(BizException.class,
                () -> service.updateRole(1L, "admin", SUPER, "ip"));
        assertTrue(downgrade.getMessage().contains("不可降级"));

        // 已有超管时不可再授予
        when(userMapper.selectById(5L)).thenReturn(user(5L, "bob", "viewer", 1, 0));
        when(userMapper.selectCount(any())).thenReturn(1L);
        BizException duplicate = assertThrows(BizException.class,
                () -> service.updateRole(5L, "super_admin", SUPER, "ip"));
        assertTrue(duplicate.getMessage().contains("已存在超级管理员"));
    }

    @Test
    void updateRoleSuccessRevokesAndLogs() {
        User target = user(5L, "bob", "viewer", 1, 0);
        when(userMapper.selectById(5L)).thenReturn(target);
        when(userMapper.selectCount(any())).thenReturn(0L);

        AdminUserService.AdminUserView view = service.updateRole(5L, "editor", SUPER, "ip");
        assertEquals("editor", view.role());
        verify(authService).invalidateUserAuth(5L);
        verify(systemLogService).record(eq(1L), eq("user"), eq("role_change"), eq("user"), eq(5L),
                eq("editor"), eq("ip"));
    }

    @Test
    void updateRoleConcurrentDuplicateSuperAdmin409() {
        User target = user(5L, "bob", "viewer", 1, 0);
        when(userMapper.selectById(5L)).thenReturn(target);
        when(userMapper.selectCount(any())).thenReturn(0L);
        doThrow(new DuplicateKeyException("uk_single_super_admin"))
                .when(userMapper).updateById(any(User.class));
        BizException ex = assertThrows(BizException.class,
                () -> service.updateRole(5L, "super_admin", SUPER, "ip"));
        assertTrue(ex.getMessage().contains("已存在超级管理员"));
    }
}
