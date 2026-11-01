package com.linblog.module.stats.service;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.config.AppProperties;
import com.linblog.module.content.mapper.ContentMapper;
import com.linblog.module.diary.mapper.DiaryMapper;
import com.linblog.module.stats.entity.ViewLog;
import com.linblog.module.stats.mapper.ViewLogMapper;
import com.linblog.support.TestAppProperties;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** §7.6 浏览计数：visitor_key 哈希、30 分钟去重、计数递增。 */
class ViewServiceTest {

    private ViewLogMapper viewLogMapper;
    private ContentMapper contentMapper;
    private DiaryMapper diaryMapper;
    private ViewService service;

    private static final AuthPrincipal USER =
            new AuthPrincipal(7L, "u", "viewer", false, Set.of());

    @BeforeEach
    void setUp() {
        viewLogMapper = mock(ViewLogMapper.class);
        contentMapper = mock(ContentMapper.class);
        diaryMapper = mock(DiaryMapper.class);
        AppProperties props = TestAppProperties.defaults();
        service = new ViewService(viewLogMapper, contentMapper, diaryMapper, props);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(AuthPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, null));
    }

    @Test
    void rejectsUnknownTypeAndMissingTarget() {
        assertThrows(BizException.class, () -> service.report("post", 1L, "1.1.1.1", "UA"));
        assertThrows(BizException.class, () -> service.report("content", null, "1.1.1.1", "UA"));
    }

    @Test
    void guestVisitorKeyHashesIpAndUaAndCounts() {
        when(viewLogMapper.selectCount(any())).thenReturn(0L);
        boolean counted = service.report("content", 11L, "2.2.2.2", "Mozilla/5.0");
        assertTrue(counted);

        ArgumentCaptor<ViewLog> captor = ArgumentCaptor.forClass(ViewLog.class);
        verify(viewLogMapper).insert(captor.capture());
        ViewLog row = captor.getValue();
        assertNull(row.getUserId());
        assertEquals("content", row.getTargetType());
        assertEquals(11L, row.getTargetId());
        // 游客 key = SHA-256(ip + "|" + ua)，不落明文 IP
        assertEquals(64, row.getVisitorKey().length());
        assertTrue(row.getVisitorKey().matches("[0-9a-f]{64}"));
        verify(contentMapper).incrViews(11L);
        verify(diaryMapper, never()).incrViews(any());
    }

    @Test
    void loggedVisitorKeyHashesUserId() {
        loginAs(USER);
        when(viewLogMapper.selectCount(any())).thenReturn(0L);
        assertTrue(service.report("diary", 5L, "3.3.3.3", "UA"));
        ArgumentCaptor<ViewLog> captor = ArgumentCaptor.forClass(ViewLog.class);
        verify(viewLogMapper).insert(captor.capture());
        assertEquals(64, captor.getValue().getVisitorKey().length());
        assertEquals(7L, captor.getValue().getUserId());
        verify(diaryMapper).incrViews(5L);
        verify(contentMapper, never()).incrViews(any());
    }

    @Test
    void duplicateWithinWindowNotCounted() {
        when(viewLogMapper.selectCount(any())).thenReturn(1L);
        boolean counted = service.report("content", 11L, "2.2.2.2", "Mozilla/5.0");
        assertFalse(counted);
        verify(viewLogMapper, never()).insert(any(ViewLog.class));
        verify(contentMapper, never()).incrViews(any());
    }

    @Test
    void portalTypeDoesNotIncrementCounters() {
        when(viewLogMapper.selectCount(any())).thenReturn(0L);
        assertTrue(service.report("portal", null, "4.4.4.4", "UA"));
        ArgumentCaptor<ViewLog> captor = ArgumentCaptor.forClass(ViewLog.class);
        verify(viewLogMapper).insert(captor.capture());
        assertNull(captor.getValue().getTargetId(), "门户浏览无具体目标");
        verify(contentMapper, never()).incrViews(any());
        verify(diaryMapper, never()).incrViews(any());
    }

    @Test
    void errorCodeIsBadRequest() {
        BizException ex = assertThrows(BizException.class,
                () -> service.report("diary", null, "1.1.1.1", "UA"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }
}
