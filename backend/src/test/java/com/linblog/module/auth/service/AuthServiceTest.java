package com.linblog.module.auth.service;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.HashUtil;
import com.linblog.config.AppProperties;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.auth.entity.PasswordResetToken;
import com.linblog.module.auth.mapper.PasswordResetTokenMapper;
import com.linblog.module.user.entity.User;
import com.linblog.module.user.mapper.UserMapper;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.JwtService;
import com.linblog.security.UserAuthStateService;
import com.linblog.support.TestAppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** §5.1 认证服务：注册/登录/刷新/登出/me/密码三端点。 */
class AuthServiceTest {

    private UserMapper userMapper;
    private RefreshTokenService refreshTokenService;
    private PasswordResetTokenMapper resetTokenMapper;
    private UserAuthStateService authStateService;
    private SystemLogService systemLogService;
    private AuthService service;
    private PasswordEncoder encoder;

    private static User user(long id, String username, String email, String passwordHash,
                             String role, Integer status, int tv) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setEmail(email);
        u.setPasswordHash(passwordHash);
        u.setNickname(username);
        u.setRoleCode(role);
        u.setStatus(status);
        u.setTokenVersion(tv);
        return u;
    }

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        refreshTokenService = mock(RefreshTokenService.class);
        resetTokenMapper = mock(PasswordResetTokenMapper.class);
        authStateService = mock(UserAuthStateService.class);
        systemLogService = mock(SystemLogService.class);
        encoder = new BCryptPasswordEncoder(4);
        AppProperties props = TestAppProperties.defaults();
        service = new AuthService(userMapper, refreshTokenService, resetTokenMapper, encoder,
                new JwtService(props), authStateService, systemLogService, props);
    }

    @Test
    void registerCreatesViewerAndLogs() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        doAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(100L);
            return 1;
        }).when(userMapper).insert(any(User.class));

        AuthService.UserView view = service.register("neo", "neo@linlog.com", "Password8", "1.1.1.1");

        assertEquals("neo", view.username());
        assertEquals("neo@linlog.com", view.email());
        assertEquals("viewer", view.role());
        assertEquals(1, view.status());
        verify(userMapper).insert(any(User.class));
        verify(systemLogService).record(eq(100L), eq("auth"), eq("register"), eq("user"), eq(100L),
                eq(null), eq("1.1.1.1"));
        verify(refreshTokenService, never()).create(anyLong(), any(), any());
    }

    @Test
    void registerDuplicateUsername409() {
        when(userMapper.selectCount(any())).thenReturn(1L);
        BizException ex = assertThrows(BizException.class,
                () -> service.register("taken", "a@b.c", "Password8", "ip"));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("用户名"));
    }

    @Test
    void registerConcurrentDuplicate409() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        doThrow(new DuplicateKeyException("dup")).when(userMapper).insert(any(User.class));
        BizException ex = assertThrows(BizException.class,
                () -> service.register("racer", "r@b.c", "Password8", "ip"));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
    }

    @Test
    void loginByUsernameIssuesTokens() {
        User u = user(9L, "alice", "alice@linlog.com", encoder.encode("Password8"), "editor", 1, 0);
        when(userMapper.selectOne(any())).thenReturn(u);
        when(refreshTokenService.create(eq(9L), any(), any()))
                .thenReturn(new RefreshTokenService.Issued("raw-refresh", "f-1"));

        AuthService.LoginResult result = service.login("alice", "Password8", "1.2.3.4", "UA");
        assertEquals(9L, result.uid());
        assertEquals("raw-refresh", result.refreshToken());
        assertFalse(result.accessToken().isBlank());
        assertEquals("alice", result.user().username());
    }

    @Test
    void loginWrongPassword401() {
        User u = user(9L, "alice", "alice@linlog.com", encoder.encode("Password8"), "editor", 1, 0);
        when(userMapper.selectOne(any())).thenReturn(u);
        BizException ex = assertThrows(BizException.class,
                () -> service.login("alice", "wrong-pass", "ip", "ua"));
        assertEquals(ErrorCode.UNAUTHENTICATED, ex.getErrorCode());
        verify(refreshTokenService, never()).create(anyLong(), any(), any());
    }

    @Test
    void loginUnknownAccount401() {
        when(userMapper.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class, () -> service.login("ghost", "Password8", "ip", "ua"));
    }

    @Test
    void loginDisabledAccount403() {
        User u = user(9L, "alice", "alice@linlog.com", encoder.encode("Password8"), "editor", 0, 0);
        when(userMapper.selectOne(any())).thenReturn(u);
        BizException ex = assertThrows(BizException.class,
                () -> service.login("alice", "Password8", "ip", "ua"));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("禁用"));
        verify(refreshTokenService, never()).create(anyLong(), any(), any());
    }

    @Test
    void loginNullStatusTreatedDisabled() {
        User u = user(9L, "alice", "alice@linlog.com", encoder.encode("Password8"), "editor", null, 0);
        when(userMapper.selectOne(any())).thenReturn(u);
        assertThrows(BizException.class, () -> service.login("alice", "Password8", "ip", "ua"));
    }

    @Test
    void refreshRotatesAndReissuesAccess() {
        when(refreshTokenService.rotate(any(), any(), any()))
                .thenReturn(new RefreshTokenService.Rotated(9L, "new-raw"));
        when(userMapper.selectById(9L))
                .thenReturn(user(9L, "alice", "a@b.c", "h", "admin", 1, 3));

        AuthService.LoginResult result = service.refresh("old-raw", "ip", "ua");
        assertEquals("new-raw", result.refreshToken());
        assertEquals(9L, result.uid());
    }

    @Test
    void refreshUserGone40102() {
        when(refreshTokenService.rotate(any(), any(), any()))
                .thenReturn(new RefreshTokenService.Rotated(9L, "new-raw"));
        when(userMapper.selectById(9L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.refresh("old", "ip", "ua"));
        assertEquals(ErrorCode.REFRESH_INVALID, ex.getErrorCode());
    }

    @Test
    void refreshDisabledUser403() {
        when(refreshTokenService.rotate(any(), any(), any()))
                .thenReturn(new RefreshTokenService.Rotated(9L, "new-raw"));
        when(userMapper.selectById(9L))
                .thenReturn(user(9L, "alice", "a@b.c", "h", "admin", 0, 3));
        assertThrows(BizException.class, () -> service.refresh("old", "ip", "ua"));
    }

    @Test
    void logoutRevokesFamily() {
        when(refreshTokenService.familyOf("raw")).thenReturn("f-1");
        service.logout("raw");
        verify(refreshTokenService).revokeFamily("f-1");
    }

    @Test
    void logoutWithoutTokenSafe() {
        when(refreshTokenService.familyOf(null)).thenReturn(null);
        service.logout(null);
        verify(refreshTokenService, never()).revokeFamily(any());
    }

    @Test
    void meReturnsLatestUserAndPermissions() {
        when(userMapper.selectById(2L)).thenReturn(user(2L, "ed", "e@b.c", "h", "editor", 1, 0));
        AuthPrincipal principal = new AuthPrincipal(2L, "ed", "editor", false,
                Set.of("article.create"));
        AuthService.MeResult me = service.me(principal);
        assertEquals("ed", me.user().username());
        assertEquals(Set.of("article.create"), me.permissions());
        assertFalse(me.isSuperAdmin());
    }

    @Test
    void meDeletedUser401() {
        when(userMapper.selectById(2L)).thenReturn(null);
        assertThrows(BizException.class, () -> service.me(
                new AuthPrincipal(2L, "x", "viewer", false, Set.of())));
    }

    @Test
    void resetRequestUnknownEmailIsSilent() {
        when(userMapper.selectOne(any())).thenReturn(null);
        service.resetRequest("nobody@linlog.com", "ip");
        verify(resetTokenMapper, never()).insert(any(PasswordResetToken.class));
    }

    @Test
    void resetRequestStoresHashAndInvalidatesOld() {
        User u = user(9L, "alice", "alice@linlog.com", "h", "viewer", 1, 0);
        when(userMapper.selectOne(any())).thenReturn(u);

        service.resetRequest("alice@linlog.com", "1.1.1.1");

        verify(resetTokenMapper).invalidateAllForUser(eq(9L), any());
        ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(resetTokenMapper).insert(captor.capture());
        PasswordResetToken row = captor.getValue();
        assertEquals(9L, row.getUserId());
        assertEquals(64, row.getTokenHash().length());
        assertTrue(row.getExpiresAt().isAfter(LocalDateTime.now().plusMinutes(25)));
        assertTrue(row.getExpiresAt().isBefore(LocalDateTime.now().plusMinutes(31)));
    }

    @Test
    void resetAppliesNewPasswordAndBumpsTv() {
        String raw = HashUtil.randomToken();
        PasswordResetToken row = new PasswordResetToken();
        row.setId(5L);
        row.setUserId(9L);
        row.setTokenHash(HashUtil.sha256Hex(raw));
        row.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        when(resetTokenMapper.selectOne(any())).thenReturn(row);
        User u = user(9L, "alice", "a@b.c", "old", "viewer", 1, 2);
        when(userMapper.selectById(9L)).thenReturn(u);
        when(resetTokenMapper.markUsed(eq(5L), any())).thenReturn(1);

        service.reset(raw, "NewPassword1", "ip");

        assertTrue(encoder.matches("NewPassword1", u.getPasswordHash()));
        assertEquals(3, u.getTokenVersion());
        verify(resetTokenMapper).markUsed(eq(5L), any());
        verify(refreshTokenService).revokeAllForUser(9L);
        verify(authStateService).evict(9L);
    }

    @Test
    void resetInvalidOrUsedOrExpiredTokenRejected() {
        when(resetTokenMapper.selectOne(any())).thenReturn(null);
        BizException none = assertThrows(BizException.class,
                () -> service.reset("ghost", "NewPassword1", "ip"));
        assertTrue(none.getMessage().contains("无效或已过期"));

        PasswordResetToken used = new PasswordResetToken();
        used.setId(5L);
        used.setUserId(9L);
        used.setTokenHash("h");
        used.setUsedAt(LocalDateTime.now().minusMinutes(1));
        used.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        when(resetTokenMapper.selectOne(any())).thenReturn(used);
        assertThrows(BizException.class, () -> service.reset("raw", "NewPassword1", "ip"));

        PasswordResetToken expired = new PasswordResetToken();
        expired.setId(5L);
        expired.setUserId(9L);
        expired.setTokenHash("h");
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(resetTokenMapper.selectOne(any())).thenReturn(expired);
        assertThrows(BizException.class, () -> service.reset("raw", "NewPassword1", "ip"));

        assertThrows(BizException.class, () -> service.reset(null, "NewPassword1", "ip"));
    }

    @Test
    void resetUserGoneRejected() {
        PasswordResetToken row = new PasswordResetToken();
        row.setId(5L);
        row.setUserId(404L);
        row.setTokenHash("h");
        row.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        when(resetTokenMapper.selectOne(any())).thenReturn(row);
        when(userMapper.selectById(404L)).thenReturn(null);
        assertThrows(BizException.class, () -> service.reset("raw", "NewPassword1", "ip"));
    }

    @Test
    void resetConcurrentMarkUsedFailureRejected() {
        PasswordResetToken row = new PasswordResetToken();
        row.setId(5L);
        row.setUserId(9L);
        row.setTokenHash("h");
        row.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        when(resetTokenMapper.selectOne(any())).thenReturn(row);
        when(userMapper.selectById(9L)).thenReturn(user(9L, "a", "a@b.c", "h", "viewer", 1, 0));
        when(resetTokenMapper.markUsed(eq(5L), any())).thenReturn(0);
        assertThrows(BizException.class, () -> service.reset("raw", "NewPassword1", "ip"));
    }

    @Test
    void changeVerifiesOldPasswordAndBumpsTv() {
        User u = user(9L, "alice", "a@b.c", encoder.encode("OldPass99"), "viewer", 1, 0);
        when(userMapper.selectById(9L)).thenReturn(u);
        AuthPrincipal principal = new AuthPrincipal(9L, "alice", "viewer", false, Set.of());

        service.change(principal, "OldPass99", "NewPass88", "ip");

        assertTrue(encoder.matches("NewPass88", u.getPasswordHash()));
        assertEquals(1, u.getTokenVersion());
        verify(refreshTokenService).revokeAllForUser(9L);
        verify(authStateService).evict(9L);
        verify(systemLogService).record(eq(9L), eq("auth"), eq("password_change"),
                eq("user"), eq(9L), eq(null), eq("ip"));
    }

    @Test
    void changeWrongOldPassword400() {
        User u = user(9L, "alice", "a@b.c", encoder.encode("OldPass99"), "viewer", 1, 0);
        when(userMapper.selectById(9L)).thenReturn(u);
        BizException ex = assertThrows(BizException.class, () -> service.change(
                new AuthPrincipal(9L, "alice", "viewer", false, Set.of()),
                "wrong", "NewPass88", "ip"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("旧密码"));
    }

    @Test
    void invalidateUserAuthRevokesAndEvicts() {
        service.invalidateUserAuth(9L);
        verify(refreshTokenService).revokeAllForUser(9L);
        verify(authStateService).evict(9L);
    }
}
