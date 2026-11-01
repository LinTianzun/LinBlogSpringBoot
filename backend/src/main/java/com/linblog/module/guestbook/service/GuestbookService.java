package com.linblog.module.guestbook.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.comment.dto.CommentView;
import com.linblog.module.comment.entity.Comment;
import com.linblog.module.comment.mapper.CommentMapper;
import com.linblog.module.comment.service.CommentService;
import com.linblog.module.guestbook.dto.GuestbookCreateRequest;
import com.linblog.module.guestbook.dto.GuestbookCreateResult;
import com.linblog.module.guestbook.dto.GuestbookView;
import com.linblog.module.guestbook.entity.Guestbook;
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
 * 留言墙（§5.6）：GET /guestbook 公开（visible 一级 + 两级回复，主动留言全量可见）；
 * POST /guestbook 登录（replyToId 非空走 comments 表回复）；DELETE 作者或 message.wallModerate。
 * 文档口径：无 pending 审核态——先发后审，逻辑删即下线。
 */
@Service
public class GuestbookService {

    private static final String STATE_VISIBLE = "visible";
    private static final String STATE_DELETED = "deleted";

    private final GuestbookMapper guestbookMapper;
    private final CommentMapper commentMapper;
    private final CommentService commentService;
    private final UserMapper userMapper;
    private final SystemLogService systemLogService;

    public GuestbookService(GuestbookMapper guestbookMapper,
                            CommentMapper commentMapper,
                            CommentService commentService,
                            UserMapper userMapper,
                            SystemLogService systemLogService) {
        this.guestbookMapper = guestbookMapper;
        this.commentMapper = commentMapper;
        this.commentService = commentService;
        this.userMapper = userMapper;
        this.systemLogService = systemLogService;
    }

    /** GET /guestbook：全部 visible 一级留言 + 回复树（回复来自 comments，两级）。 */
    public List<GuestbookView> list() {
        List<Guestbook> entries = guestbookMapper.selectList(new LambdaQueryWrapper<Guestbook>()
                .eq(Guestbook::getStatus, STATE_VISIBLE)
                .orderByDesc(Guestbook::getCreatedAt)
                .orderByDesc(Guestbook::getId));
        if (entries.isEmpty()) {
            return List.of();
        }
        Set<Long> entryIds = new HashSet<>();
        for (Guestbook entry : entries) {
            entryIds.add(entry.getId());
        }
        AuthPrincipal principal = CurrentUser.get();
        Long viewerUid = principal == null ? null : principal.uid();
        List<Comment> replies = commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getTargetType, CommentService.DB_GUESTBOOK)
                .in(Comment::getTargetId, entryIds)
                .eq(Comment::getStatus, STATE_VISIBLE)
                .orderByAsc(Comment::getCreatedAt)
                .orderByAsc(Comment::getId));
        List<CommentView> replyViews = commentService.toViews(replies, CommentService.TYPE_GUESTBOOK, viewerUid);

        Map<Long, List<CommentView>> directByEntry = new HashMap<>();
        Map<Long, List<CommentView>> subsByParent = new HashMap<>();
        for (CommentView view : replyViews) {
            if (view.parentId() == null) {
                directByEntry.computeIfAbsent(view.targetId(), k -> new ArrayList<>()).add(view);
            } else {
                subsByParent.computeIfAbsent(view.parentId(), k -> new ArrayList<>()).add(view);
            }
        }

        Map<Long, String> names = loadEntryNames(entries);
        List<GuestbookView> views = new ArrayList<>(entries.size());
        for (Guestbook entry : entries) {
            List<CommentView> directs = directByEntry.getOrDefault(entry.getId(), List.of());
            List<CommentView> tree = new ArrayList<>(directs.size());
            for (CommentView direct : directs) {
                tree.add(attachSubReplies(direct, subsByParent));
            }
            views.add(new GuestbookView(entry.getId(), entry.getUserId(),
                    names.get(entry.getUserId()), entry.getBody(), entry.getCreatedAt(), tree));
        }
        return views;
    }

    /** POST /guestbook：replyToId 空=一级留言；非空=回复（guestbook 条目或某条回复）。 */
    @Transactional
    public GuestbookCreateResult create(GuestbookCreateRequest request) {
        AuthPrincipal principal = requirePrincipal();
        String body = request.body() == null ? "" : request.body().trim();
        if (body.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "留言内容不能为空");
        }
        if (body.length() > 2000) {
            throw new BizException(ErrorCode.BAD_REQUEST, "留言内容过长");
        }

        if (request.replyToId() == null) {
            Guestbook entry = new Guestbook();
            entry.setUserId(principal.uid());
            entry.setBody(body);
            entry.setStatus(STATE_VISIBLE);
            entry.setCreatedAt(LocalDateTime.now());
            guestbookMapper.insert(entry);
            GuestbookView view = new GuestbookView(entry.getId(), entry.getUserId(),
                    usernameOf(principal.uid()), entry.getBody(), entry.getCreatedAt(), List.of());
            return GuestbookCreateResult.entry(view);
        }

        Comment comment = new Comment();
        comment.setTargetType(CommentService.DB_GUESTBOOK);
        comment.setAuthorId(principal.uid());
        comment.setBody(body);
        comment.setPraiseCount(0);
        comment.setStatus(STATE_VISIBLE);
        comment.setCreatedAt(LocalDateTime.now());

        Guestbook entry = guestbookMapper.selectById(request.replyToId());
        if (entry != null && STATE_VISIBLE.equals(entry.getStatus())) {
            comment.setTargetId(entry.getId());
        } else {
            // 回复楼中楼：replyToId 是评论 id；两级封顶——挂到其所属一级回复下
            Comment replyTarget = commentMapper.selectById(request.replyToId());
            if (replyTarget == null || !STATE_VISIBLE.equals(replyTarget.getStatus())
                    || !CommentService.DB_GUESTBOOK.equals(replyTarget.getTargetType())
                    || replyTarget.getTargetId() == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "回复目标不存在");
            }
            Guestbook parentEntry = guestbookMapper.selectById(replyTarget.getTargetId());
            if (parentEntry == null || !STATE_VISIBLE.equals(parentEntry.getStatus())) {
                throw new BizException(ErrorCode.NOT_FOUND, "回复目标不存在");
            }
            comment.setTargetId(replyTarget.getTargetId());
            comment.setParentId(replyTarget.getParentId() != null ? replyTarget.getParentId() : replyTarget.getId());
            comment.setReplyToCommentId(replyTarget.getId());
            comment.setReplyToUserId(replyTarget.getAuthorId());
        }
        commentMapper.insert(comment);
        CommentView view = commentService.toView(comment, CommentService.TYPE_GUESTBOOK, principal.uid());
        return GuestbookCreateResult.reply(view);
    }

    /** DELETE /guestbook/{id}：作者或 message.wallModerate，逻辑删。 */
    @Transactional
    public void delete(long id) {
        AuthPrincipal principal = requirePrincipal();
        Guestbook entry = guestbookMapper.selectById(id);
        if (entry == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "留言不存在");
        }
        if (STATE_DELETED.equals(entry.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "留言已删除");
        }
        boolean moderator = principal.has(PermissionKeys.MESSAGE_WALL_MODERATE);
        if (!moderator && (entry.getUserId() == null || entry.getUserId() != principal.uid())) {
            throw new BizException(ErrorCode.NOT_FOUND, "留言不存在");
        }
        entry.setStatus(STATE_DELETED);
        guestbookMapper.updateById(entry);
        systemLogService.record(principal.uid(), "guestbook", "delete", "guestbook", id, null, null);
    }

    private CommentView attachSubReplies(CommentView direct, Map<Long, List<CommentView>> subsByParent) {
        List<CommentView> subs = subsByParent.get(direct.id());
        if (subs == null || subs.isEmpty()) {
            return direct;
        }
        List<CommentView> nested = new ArrayList<>(subs.size());
        for (CommentView sub : subs) {
            nested.add(attachSubReplies(sub, subsByParent));
        }
        return new CommentView(direct.id(), direct.targetType(), direct.targetId(), direct.authorId(),
                direct.authorName(), direct.parentId(), direct.replyToCommentId(), direct.replyToUserId(),
                direct.replyToUserName(), direct.body(), direct.praiseCount(), direct.liked(),
                direct.createdAt(), nested);
    }

    private Map<Long, String> loadEntryNames(List<Guestbook> entries) {
        Set<Long> userIds = new HashSet<>();
        for (Guestbook entry : entries) {
            if (entry.getUserId() != null) {
                userIds.add(entry.getUserId());
            }
        }
        Map<Long, String> names = new HashMap<>();
        if (userIds.isEmpty()) {
            return names;
        }
        for (User user : userMapper.selectBatchIds(userIds)) {
            names.put(user.getId(), user.getUsername());
        }
        return names;
    }

    private String usernameOf(long uid) {
        User user = userMapper.selectById(uid);
        return user == null ? null : user.getUsername();
    }

    private AuthPrincipal requirePrincipal() {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未登录");
        }
        return principal;
    }
}
