package com.linblog.module.stats.service;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.comment.mapper.CommentMapper;
import com.linblog.module.content.mapper.ContentMapper;
import com.linblog.module.media.mapper.FileAttachmentMapper;
import com.linblog.module.message.mapper.ConversationMapper;
import com.linblog.module.stats.dto.SummaryResult;
import com.linblog.module.stats.dto.VisitStatsResult;
import com.linblog.module.stats.mapper.ViewLogMapper;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** §5.8 概览统计：summary 六项、visit-stats week/moon 补零。 */
class OverviewServiceTest {

    private ContentMapper contentMapper;
    private CommentMapper commentMapper;
    private ConversationMapper conversationMapper;
    private FileAttachmentMapper fileAttachmentMapper;
    private ViewLogMapper viewLogMapper;
    private OverviewService service;

    private static final AuthPrincipal ADMIN = new AuthPrincipal(1L, "root", "super_admin", true,
            Set.of("overview.view"));

    @BeforeEach
    void setUp() {
        contentMapper = mock(ContentMapper.class);
        commentMapper = mock(CommentMapper.class);
        conversationMapper = mock(ConversationMapper.class);
        fileAttachmentMapper = mock(FileAttachmentMapper.class);
        viewLogMapper = mock(ViewLogMapper.class);
        service = new OverviewService(contentMapper, commentMapper, conversationMapper,
                fileAttachmentMapper, viewLogMapper);
    }

    @Test
    void anonymousRejected() {
        BizException ex = assertThrows(BizException.class, () -> service.summary(null));
        assertEquals(ErrorCode.UNAUTHENTICATED, ex.getErrorCode());
    }

    @Test
    void missingOverviewViewRejected() {
        AuthPrincipal noPerm = new AuthPrincipal(2L, "ed", "editor", false, Set.of());
        BizException ex = assertThrows(BizException.class, () -> service.summary(noPerm));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void summaryCountsAllDomains() {
        when(contentMapper.selectCount(any())).thenReturn(12L);
        when(commentMapper.selectCount(any())).thenReturn(34L);
        when(conversationMapper.sumUnread()).thenReturn(5L);
        when(fileAttachmentMapper.selectCount(any())).thenReturn(7L);
        when(viewLogMapper.selectCount(any())).thenReturn(99L);

        SummaryResult result = service.summary(ADMIN);

        assertEquals(12L, result.contents());
        assertEquals(34L, result.comments());
        assertEquals(5L, result.unreadMessages());
        assertEquals(7L, result.files());
        assertEquals(99L, result.viewsToday());
        assertEquals(99L, result.viewsWeek());
    }

    @Test
    void visitStatsWeekPadsMissingDays() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        when(viewLogMapper.selectDailyCounts(any())).thenReturn(List.of(
                Map.of("day", java.sql.Date.valueOf(yesterday), "total", 4L)));

        VisitStatsResult result = service.visitStats("week", ADMIN);

        assertEquals("week", result.range());
        assertEquals(7, result.points().size());
        assertEquals(today, result.points().get(6).date());
        assertEquals(4L, result.points().get(5).count());
        assertEquals(0L, result.points().get(0).count());
    }

    @Test
    void visitStatsMoonReturnsThirtyPoints() {
        when(viewLogMapper.selectDailyCounts(any())).thenReturn(List.of());
        VisitStatsResult result = service.visitStats("moon", ADMIN);
        assertEquals("moon", result.range());
        assertEquals(30, result.points().size());
    }

    @Test
    void visitStatsBlankRangeDefaultsToWeek() {
        when(viewLogMapper.selectDailyCounts(any())).thenReturn(List.of());
        assertEquals("week", service.visitStats(null, ADMIN).range());
        assertEquals("week", service.visitStats("  ", ADMIN).range());
    }

    @Test
    void visitStatsUnknownRangeRejected() {
        BizException ex = assertThrows(BizException.class, () -> service.visitStats("year", ADMIN));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("range"));
    }
}
