package com.linblog.module.stats.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.comment.entity.Comment;
import com.linblog.module.comment.mapper.CommentMapper;
import com.linblog.module.content.entity.Content;
import com.linblog.module.content.mapper.ContentMapper;
import com.linblog.module.media.entity.FileAttachment;
import com.linblog.module.media.mapper.FileAttachmentMapper;
import com.linblog.module.message.mapper.ConversationMapper;
import com.linblog.module.permission.PermissionKeys;
import com.linblog.module.stats.dto.SummaryResult;
import com.linblog.module.stats.dto.VisitStatsResult;
import com.linblog.module.stats.entity.ViewLog;
import com.linblog.module.stats.mapper.ViewLogMapper;
import com.linblog.security.AuthPrincipal;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 概览统计（§5.8，M4）：summary（内容/评论/未读私信/文件量、今日与本周浏览）
 * 与 visit-stats（week=7 天 / moon=30 天折线，缺数日补零）。
 */
@Service
public class OverviewService {

    private final ContentMapper contentMapper;
    private final CommentMapper commentMapper;
    private final ConversationMapper conversationMapper;
    private final FileAttachmentMapper fileAttachmentMapper;
    private final ViewLogMapper viewLogMapper;

    public OverviewService(ContentMapper contentMapper, CommentMapper commentMapper,
                           ConversationMapper conversationMapper, FileAttachmentMapper fileAttachmentMapper,
                           ViewLogMapper viewLogMapper) {
        this.contentMapper = contentMapper;
        this.commentMapper = commentMapper;
        this.conversationMapper = conversationMapper;
        this.fileAttachmentMapper = fileAttachmentMapper;
        this.viewLogMapper = viewLogMapper;
    }

    public SummaryResult summary(AuthPrincipal principal) {
        requirePermission(principal);
        long contents = contentMapper.selectCount(new LambdaQueryWrapper<Content>()
                .ne(Content::getState, "deleted"));
        long comments = commentMapper.selectCount(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getStatus, "visible"));
        long unreadMessages = conversationMapper.sumUnread();
        long files = fileAttachmentMapper.selectCount(new LambdaQueryWrapper<FileAttachment>()
                .eq(FileAttachment::getState, "active"));
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long viewsToday = viewLogMapper.selectCount(new LambdaQueryWrapper<ViewLog>()
                .ge(ViewLog::getViewedAt, todayStart));
        long viewsWeek = viewLogMapper.selectCount(new LambdaQueryWrapper<ViewLog>()
                .ge(ViewLog::getViewedAt, LocalDateTime.now().minusDays(7)));
        return new SummaryResult(contents, comments, unreadMessages, files, viewsToday, viewsWeek);
    }

    public VisitStatsResult visitStats(String range, AuthPrincipal principal) {
        requirePermission(principal);
        String r = range == null || range.isBlank() ? "week" : range;
        int days = switch (r) {
            case "week" -> 7;
            case "moon" -> 30;
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "range 取值不合法");
        };
        LocalDate from = LocalDate.now().minusDays(days - 1L);
        Map<LocalDate, Long> counted = new HashMap<>();
        for (Map<String, Object> row : viewLogMapper.selectDailyCounts(from.atStartOfDay())) {
            Object day = row.get("day");
            if (day == null) {
                continue;
            }
            counted.put(LocalDate.parse(String.valueOf(day)), ((Number) row.get("total")).longValue());
        }
        List<VisitStatsResult.DailyVisit> points = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            LocalDate d = from.plusDays(i);
            points.add(new VisitStatsResult.DailyVisit(d, counted.getOrDefault(d, 0L)));
        }
        return new VisitStatsResult(r, points);
    }

    private void requirePermission(AuthPrincipal principal) {
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未登录");
        }
        if (!principal.has(PermissionKeys.OVERVIEW_VIEW)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问");
        }
    }
}
