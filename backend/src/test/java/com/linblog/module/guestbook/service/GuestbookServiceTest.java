package com.linblog.module.guestbook.service;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.comment.dto.CommentView;
import com.linblog.module.comment.entity.Comment;
import com.linblog.module.comment.entity.CommentPraise;
import com.linblog.module.comment.mapper.CommentMapper;
import com.linblog.module.comment.mapper.CommentPraiseMapper;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 留言墙（§5.6）：公开列表树装配、一级留言/两级回复落库、作者或审核者逻辑删。 */
class GuestbookServiceTest {

    private GuestbookMapper guestbookMapper;
    private CommentMapper commentMapper;
    private CommentService commentService;
    private UserMapper userMapper;
    private SystemLogService systemLogService;
    private GuestbookService service;

    private AuthPrincipal moderator;

    @BeforeEach
    void setUp() {
        registerTableInfo(Guestbook.class);
        registerTableInfo(Comment.class);
        registerTableInfo(CommentPraise.class);
        guestbookMapper = mock(GuestbookMapper.class);
        commentMapper = mock(CommentMapper.class);
        commentService = mock(CommentService.class);
        userMapper = mock(UserMapper.class);
        systemLogService = mock(SystemLogService.class);
        service = new GuestbookService(guestbookMapper, commentMapper, commentService,
                userMapper, systemLogService);
        moderator = new AuthPrincipal(7L, "ed", "editor", false,
                Set.of(PermissionKeys.MESSAGE_WALL_MODERATE));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void registerTableInfo(Class<?> entity) {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), entity);
    }

    private void login(AuthPrincipal principal) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private Guestbook entry(long id, long userId, String status) {
        Guestbook g = new Guestbook();
        g.setId(id);
        g.setUserId(userId);
        g.setBody("留言" + id);
        g.setStatus(status);
        g.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        return g;
    }

    private Comment reply(long id, long entryId, Long parentId, long authorId) {
        Comment c = new Comment();
        c.setId(id);
        c.setTargetType(CommentService.DB_GUESTBOOK);
        c.setTargetId(entryId);
        c.setParentId(parentId);
        c.setAuthorId(authorId);
        c.setBody("回复" + id);
        c.setPraiseCount(0);
        c.setStatus("visible");
        c.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        return c;
    }

    private void stubUsers(long... ids) {
        List<User> users = new java.util.ArrayList<>();
        for (long id : ids) {
            User u = new User();
            u.setId(id);
            u.setUsername("user" + id);
            users.add(u);
        }
        when(userMapper.selectBatchIds(any())).thenAnswer(inv -> {
            Set<Long> wanted = inv.getArgument(0);
            return users.stream().filter(u -> wanted.contains(u.getId())).collect(Collectors.toList());
        });
    }

    private String bizMessage(Runnable run) {
        BizException e = assertThrows(BizException.class, run::run);
        return e.getMessage();
    }

    @Test
    void listReturnsEmptyWhenNoEntries() {
        when(guestbookMapper.selectList(any())).thenReturn(List.of());
        assertEquals(0, service.list().size());
    }

    @Test
    void listBuildsEntryTreeWithNamesAndNestedReplies() {
        Guestbook e1 = entry(1L, 2L, "visible");
        Guestbook e2 = entry(2L, 3L, "visible");
        when(guestbookMapper.selectList(any())).thenReturn(List.of(e1, e2));
        Comment direct = reply(10L, 2L, null, 2L);
        Comment sub = reply(20L, 2L, 10L, 3L);
        when(commentMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(direct, sub)));
        stubUsers(2L, 3L);

        // toViews 真实跑（commentService mock 只用于 toView 创建路径）
        GuestbookService real = new GuestbookService(guestbookMapper, commentMapper,
                new CommentService(commentMapper,
                        mock(CommentPraiseMapper.class), userMapper,
                        mock(com.linblog.module.content.mapper.ContentMapper.class),
                        mock(com.linblog.module.diary.mapper.DiaryMapper.class),
                        guestbookMapper, systemLogService),
                userMapper, systemLogService);

        List<GuestbookView> views = real.list();

        assertEquals(2, views.size());
        GuestbookView v1 = views.get(0);
        assertEquals(1L, v1.id());
        assertEquals("user2", v1.userName());
        assertEquals("留言1", v1.body());
        assertEquals(0, v1.replies().size());

        GuestbookView v2 = views.get(1);
        assertEquals(1, v2.replies().size());
        CommentView directView = v2.replies().get(0);
        assertEquals(10L, directView.id());
        assertEquals("user2", directView.authorName());
        assertEquals(1, directView.subReplies().size());
        assertEquals("user3", directView.subReplies().get(0).authorName());
    }

    @Test
    void createRequiresLoginAndBody() {
        BizException anonymous = assertThrows(BizException.class,
                () -> service.create(new GuestbookCreateRequest("hi", null)));
        assertEquals(ErrorCode.UNAUTHENTICATED.code(), anonymous.getErrorCode().code());

        login(moderator);
        assertEquals("留言内容不能为空", bizMessage(() -> service.create(new GuestbookCreateRequest("  ", null))));
        assertEquals("留言内容过长", bizMessage(
                () -> service.create(new GuestbookCreateRequest("x".repeat(2001), null))));
    }

    @Test
    void createTopLevelInsertsEntryAndReturnsKindEntry() {
        login(moderator);
        when(userMapper.selectById(7L)).thenReturn(null); // usernameOf 允许查无此人

        GuestbookCreateResult result = service.create(new GuestbookCreateRequest(" 大家好 ", null));

        ArgumentCaptor<Guestbook> captor = ArgumentCaptor.forClass(Guestbook.class);
        verify(guestbookMapper).insert(captor.capture());
        Guestbook inserted = captor.getValue();
        assertEquals(7L, inserted.getUserId());
        assertEquals("大家好", inserted.getBody());
        assertEquals("visible", inserted.getStatus());

        assertEquals("entry", result.kind());
        assertNotNull(result.entry());
        assertNull(result.comment());
        assertEquals("大家好", result.entry().body());
        assertEquals(0, result.entry().replies().size());
    }

    @Test
    void createReplyToVisibleEntryGoesToCommentsTable() {
        login(moderator);
        Guestbook target = entry(1L, 2L, "visible");
        when(guestbookMapper.selectById(1L)).thenReturn(target);
        CommentView mockView = new CommentView(500L, "guestbook", 1L, 7L, "ed", null,
                null, null, null, "reply", 0, false,
                LocalDateTime.of(2026, 1, 1, 0, 0), List.of());
        when(commentService.toView(any(Comment.class), any(), any())).thenReturn(mockView);

        GuestbookCreateResult result = service.create(new GuestbookCreateRequest("reply", 1L));

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentMapper).insert(captor.capture());
        Comment inserted = captor.getValue();
        assertEquals(CommentService.DB_GUESTBOOK, inserted.getTargetType());
        assertEquals(1L, inserted.getTargetId());
        assertNull(inserted.getParentId());

        assertEquals("reply", result.kind());
        assertNull(result.entry());
        assertEquals(500L, result.comment().id());
        verify(guestbookMapper, never()).insert(any(Guestbook.class));
    }

    @Test
    void createReplyToCommentAttachesUnderRoot() {
        login(moderator);
        Comment sub = reply(20L, 1L, 10L, 3L);
        when(commentMapper.selectById(20L)).thenReturn(sub);
        Guestbook parentEntry = entry(1L, 2L, "visible");
        when(guestbookMapper.selectById(1L)).thenReturn(parentEntry);
        CommentView mockView = new CommentView(501L, "guestbook", 1L, 7L, "ed", 10L,
                20L, 3L, "user3", "sub", 0, false,
                LocalDateTime.of(2026, 1, 1, 0, 0), List.of());
        when(commentService.toView(any(Comment.class), any(), any())).thenReturn(mockView);

        GuestbookCreateResult result = service.create(new GuestbookCreateRequest("sub", 20L));

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentMapper).insert(captor.capture());
        Comment inserted = captor.getValue();
        assertEquals(1L, inserted.getTargetId());
        assertEquals(10L, inserted.getParentId());
        assertEquals(20L, inserted.getReplyToCommentId());
        assertEquals(3L, inserted.getReplyToUserId());
        assertEquals("reply", result.kind());
    }

    @Test
    void createReplyToRootCommentUsesItAsParent() {
        login(moderator);
        when(guestbookMapper.selectById(1L)).thenReturn(entry(1L, 2L, "visible"));
        Comment direct = reply(10L, 1L, null, 3L);
        when(commentMapper.selectById(10L)).thenReturn(direct);
        when(commentService.toView(any(Comment.class), any(), any()))
                .thenReturn(new CommentView(502L, "guestbook", 1L, 7L, "ed", 10L,
                        10L, 3L, "user3", "r", 0, false,
                        LocalDateTime.of(2026, 1, 1, 0, 0), List.of()));

        service.create(new GuestbookCreateRequest("r", 10L));

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentMapper).insert(captor.capture());
        assertEquals(10L, captor.getValue().getParentId());
        assertEquals(10L, captor.getValue().getReplyToCommentId());
    }

    @Test
    void createReplyRejectsMissingOrInvisibleTargets() {
        login(moderator);
        // 回复目标既非留言也非评论
        when(guestbookMapper.selectById(1L)).thenReturn(null);
        when(commentMapper.selectById(99L)).thenReturn(null);
        assertEquals("回复目标不存在", bizMessage(() -> service.create(new GuestbookCreateRequest("r", 99L))));

        // 目标评论不可见
        Comment hidden = reply(20L, 1L, 10L, 3L);
        hidden.setStatus("deleted");
        when(commentMapper.selectById(20L)).thenReturn(hidden);
        assertEquals("回复目标不存在", bizMessage(() -> service.create(new GuestbookCreateRequest("r", 20L))));

        // 所属一级留言已被删
        Comment sub = reply(20L, 1L, 10L, 3L);
        when(commentMapper.selectById(20L)).thenReturn(sub);
        when(guestbookMapper.selectById(1L)).thenReturn(entry(1L, 2L, "deleted"));
        assertEquals("回复目标不存在", bizMessage(() -> service.create(new GuestbookCreateRequest("r", 20L))));

        verify(commentMapper, never()).insert(any(Comment.class));
    }

    @Test
    void deleteGuardsAndRequiresAuthorOrModerator() {
        BizException anonymous = assertThrows(BizException.class, () -> service.delete(1L));
        assertEquals(ErrorCode.UNAUTHENTICATED.code(), anonymous.getErrorCode().code());

        login(moderator);
        when(guestbookMapper.selectById(1L)).thenReturn(null);
        assertEquals("留言不存在", bizMessage(() -> service.delete(1L)));

        Guestbook deleted = entry(1L, 2L, "deleted");
        when(guestbookMapper.selectById(1L)).thenReturn(deleted);
        assertEquals("留言已删除", bizMessage(() -> service.delete(1L)));

        Guestbook foreign = entry(1L, 2L, "visible");
        when(guestbookMapper.selectById(1L)).thenReturn(foreign);
        AuthPrincipal nobody = new AuthPrincipal(9L, "guest", "viewer", false, Set.of());
        login(nobody);
        assertEquals("留言不存在", bizMessage(() -> service.delete(1L)));
    }

    @Test
    void deleteModeratorSoftDeletesAndLogs() {
        login(moderator);
        Guestbook entry = entry(1L, 2L, "visible");
        when(guestbookMapper.selectById(1L)).thenReturn(entry);

        service.delete(1L);

        assertEquals("deleted", entry.getStatus());
        verify(guestbookMapper).updateById(entry);
        verify(systemLogService).record(7L, "guestbook", "delete", "guestbook", 1L, null, null);
    }

    @Test
    void deleteAuthorOwnEntrySucceedsWithoutModerateKey() {
        AuthPrincipal author = new AuthPrincipal(2L, "alice", "viewer", false, Set.of());
        login(author);
        Guestbook own = entry(1L, 2L, "visible");
        when(guestbookMapper.selectById(1L)).thenReturn(own);

        service.delete(1L);

        assertEquals("deleted", own.getStatus());
        verify(systemLogService).record(2L, "guestbook", "delete", "guestbook", 1L, null, null);
    }
}
