package com.linblog.module.comment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.comment.dto.CommentCreateRequest;
import com.linblog.module.comment.dto.CommentThread;
import com.linblog.module.comment.dto.CommentView;
import com.linblog.module.comment.dto.PraiseResult;
import com.linblog.module.comment.entity.Comment;
import com.linblog.module.comment.entity.CommentPraise;
import com.linblog.module.comment.mapper.CommentMapper;
import com.linblog.module.comment.mapper.CommentPraiseMapper;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.content.mapper.ContentMapper;
import com.linblog.module.diary.mapper.DiaryMapper;
import com.linblog.module.guestbook.mapper.GuestbookMapper;
import com.linblog.module.permission.PermissionKeys;
import com.linblog.module.user.entity.User;
import com.linblog.module.user.mapper.UserMapper;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统一评论（§5.5）：三端共用（article/image→content、diary、guestbook）。
 * API 层 targetType 走 §4.2 白名单 article/image/diary/guestbook，服务端映射到
 * comments.target_type ∈ content/diary/guestbook（§3.3）。
 */
@Service
public class CommentService {

    public static final String TYPE_ARTICLE = "article";
    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_DIARY = "diary";
    public static final String TYPE_GUESTBOOK = "guestbook";

    public static final String DB_CONTENT = "content";
    public static final String DB_DIARY = "diary";
    public static final String DB_GUESTBOOK = "guestbook";

    private static final String STATE_VISIBLE = "visible";
    private static final String STATE_DELETED = "deleted";

    private final CommentMapper commentMapper;
    private final CommentPraiseMapper commentPraiseMapper;
    private final UserMapper userMapper;
    private final ContentMapper contentMapper;
    private final DiaryMapper diaryMapper;
    private final GuestbookMapper guestbookMapper;
    private final SystemLogService systemLogService;

    public CommentService(CommentMapper commentMapper,
                          CommentPraiseMapper commentPraiseMapper,
                          UserMapper userMapper,
                          ContentMapper contentMapper,
                          DiaryMapper diaryMapper,
                          GuestbookMapper guestbookMapper,
                          SystemLogService systemLogService) {
        this.commentMapper = commentMapper;
        this.commentPraiseMapper = commentPraiseMapper;
        this.userMapper = userMapper;
        this.contentMapper = contentMapper;
        this.diaryMapper = diaryMapper;
        this.guestbookMapper = guestbookMapper;
        this.systemLogService = systemLogService;
    }

    /** GET /portal/{targetType}/{id}/comments：一级 + 楼中楼两段，仅 status=visible。 */
    public CommentThread thread(String targetType, long targetId, AuthPrincipal principal) {
        String dbType = requireVisibleTarget(targetType, targetId);
        List<Comment> comments = commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getTargetType, dbType)
                .eq(Comment::getTargetId, targetId)
                .eq(Comment::getStatus, STATE_VISIBLE)
                .orderByAsc(Comment::getCreatedAt)
                .orderByAsc(Comment::getId));
        List<CommentView> views = toViews(comments, targetType, principal == null ? null : principal.uid());
        List<CommentView> roots = new ArrayList<>();
        List<CommentView> replies = new ArrayList<>();
        for (CommentView view : views) {
            if (view.parentId() == null) {
                roots.add(view);
            } else {
                replies.add(view);
            }
        }
        return new CommentThread(roots, replies);
    }

    /** POST /comments：目标存在且 visible；两级封顶；reply_to 冗余写入。 */
    @Transactional
    public CommentView create(CommentCreateRequest request) {
        AuthPrincipal principal = requirePrincipal();
        String targetType = request.targetType() == null ? "" : request.targetType().trim();
        if (request.targetId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评论目标不能为空");
        }
        String dbType = requireVisibleTarget(targetType, request.targetId());
        String body = request.body() == null ? "" : request.body().trim();
        if (body.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评论内容不能为空");
        }
        if (body.length() > 2000) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评论内容过长");
        }

        Comment comment = new Comment();
        comment.setTargetType(dbType);
        comment.setTargetId(request.targetId());
        comment.setAuthorId(principal.uid());
        comment.setBody(body);
        comment.setPraiseCount(0);
        comment.setStatus(STATE_VISIBLE);
        comment.setCreatedAt(LocalDateTime.now());

        Long parentId = request.parentId();
        Long replyToCommentId = request.replyToCommentId();
        Long replyToUserId = null;
        if (parentId != null) {
            Comment parent = visibleCommentOf(dbType, request.targetId(), parentId);
            if (parent.getParentId() != null) {
                // 两级封顶：回复楼中楼时挂到其所属一级评论下
                parentId = parent.getParentId();
                replyToCommentId = parent.getId();
                replyToUserId = parent.getAuthorId();
            }
        }
        if (replyToCommentId != null) {
            Comment replyTarget = visibleCommentOf(dbType, request.targetId(), replyToCommentId);
            parentId = replyTarget.getParentId() != null ? replyTarget.getParentId() : replyTarget.getId();
            replyToCommentId = replyTarget.getId();
            replyToUserId = replyTarget.getAuthorId();
        }
        comment.setParentId(parentId);
        comment.setReplyToCommentId(replyToCommentId);
        comment.setReplyToUserId(replyToUserId);

        commentMapper.insert(comment);
        if (DB_CONTENT.equals(dbType)) {
            contentMapper.incrCommentsCount(request.targetId());
        }
        return toView(comment, targetType, principal.uid());
    }

    /**
     * DELETE /comments/{id}：作者或 comment.moderate；一级删→连带回复逻辑删 +
     * 回退冗余计数（§5.5，事务）。防探测：非作者且无审核权 → 40400。
     */
    @Transactional
    public void delete(long id) {
        AuthPrincipal principal = requirePrincipal();
        Comment comment = commentMapper.selectById(id);
        if (comment == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "评论不存在");
        }
        if (STATE_DELETED.equals(comment.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "评论已删除");
        }
        boolean moderator = principal.has(PermissionKeys.COMMENT_MODERATE);
        if (!moderator && (comment.getAuthorId() == null || comment.getAuthorId() != principal.uid())) {
            throw new BizException(ErrorCode.NOT_FOUND, "评论不存在");
        }

        comment.setStatus(STATE_DELETED);
        commentMapper.updateById(comment);

        List<Comment> replies = commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getParentId, id)
                .eq(Comment::getStatus, STATE_VISIBLE));
        for (Comment reply : replies) {
            reply.setStatus(STATE_DELETED);
            commentMapper.updateById(reply);
        }

        int removed = 1 + replies.size();
        if (DB_CONTENT.equals(comment.getTargetType())) {
            contentMapper.decrCommentsCount(comment.getTargetId(), removed);
        } else if (DB_DIARY.equals(comment.getTargetType())) {
            // diaries 表无 comments_count 列（§3.2），无需回退
        }
        systemLogService.record(principal.uid(), "comment", "delete", "comment", id, null, null);
    }

    /** POST /comments/{id}/praise：toggle → {active, count}（幂等：uk 由 select 前置保证）。 */
    @Transactional
    public PraiseResult praise(long id) {
        AuthPrincipal principal = requirePrincipal();
        Comment comment = commentMapper.selectById(id);
        if (comment == null || !STATE_VISIBLE.equals(comment.getStatus())) {
            throw new BizException(ErrorCode.NOT_FOUND, "评论不存在");
        }
        boolean active;
        CommentPraise existing = commentPraiseMapper.selectOne(new LambdaQueryWrapper<CommentPraise>()
                .eq(CommentPraise::getCommentId, id)
                .eq(CommentPraise::getUserId, principal.uid()));
        if (existing != null) {
            commentPraiseMapper.delete(new LambdaQueryWrapper<CommentPraise>()
                    .eq(CommentPraise::getCommentId, id)
                    .eq(CommentPraise::getUserId, principal.uid()));
            commentMapper.decrPraise(id);
            active = false;
        } else {
            CommentPraise praise = new CommentPraise();
            praise.setCommentId(id);
            praise.setUserId(principal.uid());
            try {
                commentPraiseMapper.insert(praise);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发重复点赞：uk 兜底，视为已点赞
            }
            commentMapper.incrPraise(id);
            active = true;
        }
        Comment latest = commentMapper.selectById(id);
        long count = latest == null || latest.getPraiseCount() == null ? 0 : latest.getPraiseCount();
        return new PraiseResult(active, count);
    }

    /** 视图装配（GuestbookService 复用）：作者名 + 被回复人名 + liked 批量查询。 */
    public List<CommentView> toViews(List<Comment> comments, String apiTargetType, Long viewerUid) {
        if (comments == null || comments.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = new HashSet<>();
        Set<Long> commentIds = new HashSet<>();
        for (Comment c : comments) {
            if (c.getAuthorId() != null) {
                userIds.add(c.getAuthorId());
            }
            if (c.getReplyToUserId() != null) {
                userIds.add(c.getReplyToUserId());
            }
            commentIds.add(c.getId());
        }
        Map<Long, String> names = loadNames(userIds);
        Set<Long> likedIds = new HashSet<>();
        if (viewerUid != null && !commentIds.isEmpty()) {
            List<CommentPraise> praises = commentPraiseMapper.selectList(new LambdaQueryWrapper<CommentPraise>()
                    .eq(CommentPraise::getUserId, viewerUid)
                    .in(CommentPraise::getCommentId, commentIds));
            for (CommentPraise p : praises) {
                likedIds.add(p.getCommentId());
            }
        }
        List<CommentView> views = new ArrayList<>(comments.size());
        for (Comment c : comments) {
            views.add(new CommentView(
                    c.getId(),
                    apiTargetType,
                    c.getTargetId(),
                    c.getAuthorId(),
                    names.get(c.getAuthorId()),
                    c.getParentId(),
                    c.getReplyToCommentId(),
                    c.getReplyToUserId(),
                    names.get(c.getReplyToUserId()),
                    c.getBody(),
                    c.getPraiseCount() == null ? 0 : c.getPraiseCount(),
                    likedIds.contains(c.getId()),
                    c.getCreatedAt(),
                    null));
        }
        return views;
    }

    public CommentView toView(Comment comment, String apiTargetType, Long viewerUid) {
        return toViews(List.of(comment), apiTargetType, viewerUid).get(0);
    }

    private Map<Long, String> loadNames(Set<Long> userIds) {
        Map<Long, String> names = new HashMap<>();
        if (userIds.isEmpty()) {
            return names;
        }
        for (User user : userMapper.selectBatchIds(userIds)) {
            names.put(user.getId(), user.getUsername());
        }
        return names;
    }

    /** API targetType 白名单 → DB target_type；目标必须存在且可见（否则 40400 防探测）。 */
    public String requireVisibleTarget(String targetType, long targetId) {
        switch (targetType == null ? "" : targetType) {
            case TYPE_ARTICLE, TYPE_IMAGE -> {
                var content = contentMapper.selectById(targetId);
                if (content == null || !"published".equals(content.getState())) {
                    throw new BizException(ErrorCode.NOT_FOUND, "评论目标不存在");
                }
                return DB_CONTENT;
            }
            case TYPE_DIARY -> {
                var diary = diaryMapper.selectById(targetId);
                if (diary == null || !"published".equals(diary.getState())) {
                    throw new BizException(ErrorCode.NOT_FOUND, "评论目标不存在");
                }
                return DB_DIARY;
            }
            case TYPE_GUESTBOOK -> {
                var entry = guestbookMapper.selectById(targetId);
                if (entry == null || !STATE_VISIBLE.equals(entry.getStatus())) {
                    throw new BizException(ErrorCode.NOT_FOUND, "评论目标不存在");
                }
                return DB_GUESTBOOK;
            }
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "评论目标类型不合法");
        }
    }

    private Comment visibleCommentOf(String dbType, long targetId, long commentId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null || !STATE_VISIBLE.equals(comment.getStatus())
                || !dbType.equals(comment.getTargetType())
                || comment.getTargetId() == null
                || comment.getTargetId() != targetId) {
            throw new BizException(ErrorCode.NOT_FOUND, "被回复评论不存在");
        }
        return comment;
    }

    private AuthPrincipal requirePrincipal() {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未登录");
        }
        return principal;
    }
}
