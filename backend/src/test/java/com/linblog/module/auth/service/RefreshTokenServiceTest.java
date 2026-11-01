package com.linblog.module.auth.service;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.HashUtil;
import com.linblog.config.AppProperties;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.auth.entity.RefreshToken;
import com.linblog.module.auth.mapper.RefreshTokenMapper;
import com.linblog.module.user.mapper.UserMapper;
import com.linblog.support.TestAppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** §7.2 刷新令牌：仅存 SHA-256、family 轮换、重放检测（吊销全族 + tv+1）、过期拒绝。 */
class RefreshTokenServiceTest {

    private RefreshTokenMapper refreshTokenMapper;
    private UserMapper userMapper;
    private SystemLogService systemLogService;
    private RefreshTokenService service;
    private AppProperties props;

    @BeforeEach
    void setUp() {
        refreshTokenMapper = mock(RefreshTokenMapper.class);
        userMapper = mock(UserMapper.class);
        systemLogService = mock(SystemLogService.class);
        props = TestAppProperties.defaults();
        service = new RefreshTokenService(refreshTokenMapper, userMapper, systemLogService, props);
    }

    private RefreshToken activeToken(long id, long userId, String familyId, LocalDateTime expiresAt) {
        RefreshToken t = new RefreshToken();
        t.setId(id);
        t.setUserId(userId);
        t.setFamilyId(familyId);
        t.setTokenHash("hash-of-presented");
        t.setExpiresAt(expiresAt);
        return t;
    }

    @Test
    void createStoresSha256HashAndFamily() {
        RefreshTokenService.Issued issued = service.create(9L, "1.2.3.4", "UA/1");

        assertNotNull(issued.rawToken());
        assertNotNull(issued.familyId());
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenMapper).insert(captor.capture());
        RefreshToken row = captor.getValue();
        assertEquals(9L, row.getUserId());
        assertEquals(HashUtil.sha256Hex(issued.rawToken()), row.getTokenHash(), "只存 SHA-256，不落明文");
        assertEquals(issued.familyId(), row.getFamilyId());
        assertNull(row.getRevokedAt());
        assertTrue(row.getExpiresAt().isAfter(LocalDateTime.now().plusDays(6)));
        assertEquals("1.2.3.4", row.getCreatedIp());
        assertEquals(32, row.getCreatedUaHash().length());
    }

    @Test
    void createWithoutUaHashesEmptyString() {
        service.create(1L, null, null);
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenMapper).insert(captor.capture());
        assertNull(captor.getValue().getCreatedIp());
        assertEquals(HashUtil.sha256Hex("").substring(0, 32), captor.getValue().getCreatedUaHash());
    }

    @Test
    void rotateHappyPathRotatesWithinFamily() {
        RefreshToken current = activeToken(1L, 9L, "f-1", LocalDateTime.now().plusDays(1));
        when(refreshTokenMapper.selectOne(any())).thenReturn(current);
        when(refreshTokenMapper.markRotated(eq(1L), any(), any())).thenReturn(1);

        RefreshTokenService.Rotated rotated = service.rotate("presented-raw", "1.2.3.4", "UA/1");

        assertNotNull(rotated.rawToken());
        assertEquals(9L, rotated.userId());
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenMapper).insert(captor.capture());
        RefreshToken next = captor.getValue();
        assertEquals("f-1", next.getFamilyId(), "同 family 轮换");
        assertEquals(9L, next.getUserId());
        assertEquals(HashUtil.sha256Hex(rotated.rawToken()), next.getTokenHash());
        verify(refreshTokenMapper).markRotated(eq(1L), eq(next.getId()), any());
        verify(refreshTokenMapper, never()).revokeFamily(anyString(), any());
        verify(systemLogService, never()).record(any(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void replayOfRevokedTokenRevokesFamilyAndBumpsTv() {
        RefreshToken current = activeToken(1L, 9L, "f-1", LocalDateTime.now().plusDays(1));
        current.setRevokedAt(LocalDateTime.now().minusMinutes(5));
        when(refreshTokenMapper.selectOne(any())).thenReturn(current);

        BizException ex = assertThrows(BizException.class,
                () -> service.rotate("presented-raw", "1.2.3.4", "UA/1"));

        assertEquals(ErrorCode.REFRESH_INVALID, ex.getErrorCode());
        verify(refreshTokenMapper).revokeFamily(eq("f-1"), any());
        verify(userMapper).bumpTokenVersion(9L);
        verify(systemLogService).record(eq(9L), eq("auth"), eq("refresh_replay"), eq("user"),
                eq(9L), anyString(), eq("1.2.3.4"));
        verify(refreshTokenMapper, never()).insert(any(RefreshToken.class));
    }

    @Test
    void concurrentMarkRotatedFailureTreatedAsReplay() {
        RefreshToken current = activeToken(1L, 9L, "f-1", LocalDateTime.now().plusDays(1));
        when(refreshTokenMapper.selectOne(any())).thenReturn(current);
        when(refreshTokenMapper.markRotated(eq(1L), any(), any())).thenReturn(0);

        assertThrows(BizException.class, () -> service.rotate("presented-raw", "ip", "ua"));

        verify(refreshTokenMapper).revokeFamily(eq("f-1"), any());
        verify(userMapper).bumpTokenVersion(9L);
    }

    @Test
    void expiredTokenRejectedWithoutWrites() {
        RefreshToken current = activeToken(1L, 9L, "f-1", LocalDateTime.now().minusDays(1));
        when(refreshTokenMapper.selectOne(any())).thenReturn(current);

        BizException ex = assertThrows(BizException.class,
                () -> service.rotate("presented-raw", "ip", "ua"));

        assertEquals(ErrorCode.REFRESH_INVALID, ex.getErrorCode());
        verify(refreshTokenMapper, never()).revokeFamily(anyString(), any());
        verify(refreshTokenMapper, never()).insert(any(RefreshToken.class));
        verify(userMapper, never()).bumpTokenVersion(anyLong());
    }

    @Test
    void unknownOrBlankTokenRejected() {
        when(refreshTokenMapper.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class, () -> service.rotate("ghost", "ip", "ua"));
        assertThrows(BizException.class, () -> service.rotate(null, "ip", "ua"));
        assertThrows(BizException.class, () -> service.rotate(" ", "ip", "ua"));
        verify(refreshTokenMapper, never()).insert(any(RefreshToken.class));
    }

    @Test
    void familyOfFindsOrReturnsNull() {
        RefreshToken row = activeToken(1L, 9L, "f-9", LocalDateTime.now());
        when(refreshTokenMapper.selectOne(any())).thenReturn(row).thenReturn(null);
        assertEquals("f-9", service.familyOf("raw-1"));
        assertNull(service.familyOf("raw-2"));
        assertNull(service.familyOf(null));
    }

    @Test
    void revokeHelpersDelegate() {
        service.revokeFamily("f-1");
        verify(refreshTokenMapper).revokeFamily(eq("f-1"), any());
        service.revokeFamily(null);
        verifyNoMoreInteractions(refreshTokenMapper);

        service.revokeAllForUser(7L);
        verify(refreshTokenMapper).revokeAllForUser(eq(7L), any());
    }

    @Test
    void rotateAlwaysIssuesFreshTokens() {
        RefreshToken current = activeToken(1L, 9L, "f-1", LocalDateTime.now().plusDays(1));
        when(refreshTokenMapper.selectOne(any())).thenReturn(current);
        when(refreshTokenMapper.markRotated(any(), any(), any())).thenReturn(1);

        List<String> raws = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            raws.add(service.rotate("x" + i, "ip", "ua").rawToken());
        }
        assertNotEquals(raws.get(0), raws.get(1));
        assertNotEquals(raws.get(1), raws.get(2));
        assertTrue(raws.stream().allMatch(r -> r.length() == 43));
        Mockito.verify(refreshTokenMapper, Mockito.times(3)).insert(any(RefreshToken.class));
    }
}
