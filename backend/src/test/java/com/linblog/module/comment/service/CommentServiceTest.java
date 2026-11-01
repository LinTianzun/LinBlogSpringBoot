package com.linblog.module.comment.service;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.comment.dto.CommentCreateRequest;
import com.linblog.module.comment.dto.CommentThread;
import com.linblog.module.comment.dto.CommentView;
import com.linblog.module.comment.dto.PraiseResult;
import com.linblog.module.comment.entity.Comment;
import com.linblog.module.comment.entity.CommentPraise;
import com.linblog.module.comment.mapper.CommentMapper;
import com.linblog.module.comment.mapper.CommentPraiseMapper;
import com.linblog.module.content.entity.Content;
import com.linblog.module.content.mapper.ContentMapper;
import com.linblog.module.diary.entity.Diary;
import com.linblog.module.diary.mapper.DiaryMapper;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 统一评论（§5.5）：目标白名单映射、两级封顶、逻辑删连带、点赞 toggle、视图装配。 */
class CommentServiceTest {

    private CommentMapper commentMapper;
    private CommentPraiseMapper commentPraiseMapper;
    private UserMapper userMapper;
    private ContentMapper contentMapper;
    private DiaryMapper diaryMapper;
    private GuestbookMapper guestbookMapper;
    private SystemLogService systemLogService;
    private CommentService service;

    private AuthPrincipal moderator;

    @BeforeEach
    void setUp() {
        registerTableInfo(Comment.class);
        registerTableInfo(CommentPraise.class);
        commentMapper = mock(CommentMapper.class);
        commentPraiseMapper = mock(CommentPraiseMapper.class);
        userMapper = mock(UserMapper.class);
        contentMapper = mock(ContentMapper.class);
        diaryMapper = mock(DiaryMapper.class);
        guestbookMapper = mock(GuestbookMapper.class);
        systemLogService = mock(SystemLogService.class);
        service = new CommentService(commentMapper, commentPraiseMapper, userMapper,
                contentMapper, diaryMapper, guestbookMapper, systemLogService);
        moderator = new AuthPrincipal(7L, "ed", "editor", false,
                Set.of(PermissionKeys.COMMENT_MODERATE));
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

    private Content content(long id, String state) {
        Content c = new Content();
        c.setId(id);
        c.setState(state);
        return c;
    }

    private Diary diary(long id, String state) {
        Diary d = new Diary();
        d.setId(id);
        d.setState(state);
        return d;
    }

    private Guestbook entry(long id, String status) {
        Guestbook g = new Guestbook();
        g.setId(id);
        g.setStatus(status);
        return g;
    }

    private Comment comment(long id, String dbType, long targetId, Long parentId, Long authorId) {
        Comment c = new Comment();
        c.setId(id);
        c.setTargetType(dbType);
        c.setTargetId(targetId);
        c.setParentId(parentId);
        c.setAuthorId(authorId);
        c.setBody("评论" + id);
        c.setPraiseCount(0);
        c.setStatus("visible");
        c.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        return c;
    }

    private void stubUsers() {
        User u2 = new User();
        u2.setId(2L);
        u2.setUsername("alice");
        User u3 = new User();
        u3.setId(3L);
        u3.setUsername("bob");
        when(userMapper.selectBatchIds(any())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return List.of(u2, u3).stream()
                    .filter(u -> ids.contains(u.getId()))
                    .collect(Collectors.toList());
        });
    }

    private String bizMessage(Runnable run) {
        BizException e = assertThrows(BizException.class, run::run);
        return e.getMessage();
    }

    @Test
    void requireVisibleTargetMapsWhitelistToDbTypes() {
        when(contentMapper.selectById(11L)).thenReturn(content(11L, "published"));
        when(diaryMapper.selectById(12L)).thenReturn(diary(12L, "published"));
        when(guestbookMapper.selectById(13L)).thenReturn(entry(13L, "visible"));

        assertEquals(CommentService.DB_CONTENT, service.requireVisibleTarget("article", 11L));
        assertEquals(CommentService.DB_CONTENT, service.requireVisibleTarget("image", 11L));
        assertEquals(CommentService.DB_DIARY, service.requireVisibleTarget("diary", 12L));
        assertEquals(CommentService.DB_GUESTBOOK, service.requireVisibleTarget("guestbook", 13L));
    }

    @Test
    void requireVisibleTargetRejectsInvisibleOrUnknown() {
        when(contentMapper.selectById(11L)).thenReturn(content(11L, "draft"));
        when(diaryMapper.selectById(12L)).thenReturn(diary(12L, "draft"));
        when(guestbookMapper.selectById(13L)).thenReturn(entry(13L, "deleted"));
        when(contentMapper.selectById(99L)).thenReturn(null);

        BizException draft = assertThrows(BizException.class,
                () -> service.requireVisibleTarget("article", 11L));
        assertEquals(ErrorCode.NOT_FOUND.code(), draft.getErrorCode().code());
        BizException diaryDraft = assertThrows(BizException.class,
                () -> service.requireVisibleTarget("diary", 12L));
        assertEquals(ErrorCode.NOT_FOUND.code(), diaryDraft.getErrorCode().code());
        BizException hidden = assertThrows(BizException.class,
                () -> service.requireVisibleTarget("guestbook", 13L));
        assertEquals(ErrorCode.NOT_FOUND.code(), hidden.getErrorCode().code());
        BizException missing = assertThrows(BizException.class,
                () -> service.requireVisibleTarget("image", 99L));
        assertEquals(ErrorCode.NOT_FOUND.code(), missing.getErrorCode().code());
        BizException unknown = assertThrows(BizException.class,
                () -> service.requireVisibleTarget("video", 11L));
        assertEquals(ErrorCode.BAD_REQUEST.code(), unknown.getErrorCode().code());
        assertEquals("评论目标类型不合法", unknown.getMessage());
    }

    @Test
    void createRequiresLoginTargetAndBody() {
        BizException anonymous = assertThrows(BizException.class,
                () -> service.create(new CommentCreateRequest("article", 11L, null, null, "hi")));
        assertEquals(ErrorCode.UNAUTHENTICATED.code(), anonymous.getErrorCode().code());

        login(moderator);
        when(contentMapper.selectById(11L)).thenReturn(content(11L, "published"));
        assertEquals("评论目标不能为空", bizMessage(
                () -> service.create(new CommentCreateRequest("article", null, null, null, "hi"))));
        assertEquals("评论内容不能为空", bizMessage(
                () -> service.create(new CommentCreateRequest("article", 11L, null, null, "   "))));
        assertEquals("评论内容过长", bizMessage(
                () -> service.create(new CommentCreateRequest("article", 11L, null, null, "x".repeat(2001)))));
    }

    @Test
    void createTopLevelInsertsVisibleCommentAndIncrCount() {
        login(moderator);
        when(contentMapper.selectById(11L)).thenReturn(content(11L, "published"));

        CommentView view = service.create(new CommentCreateRequest("article", 11L, null, null, "  好文  "));

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentMapper).insert(captor.capture());
        Comment inserted = captor.getValue();
        assertEquals(CommentService.DB_CONTENT, inserted.getTargetType());
        assertEquals(11L, inserted.getTargetId());
        assertEquals(7L, inserted.getAuthorId());
        assertEquals("好文", inserted.getBody());
        assertEquals(0, inserted.getPraiseCount());
        assertEquals("visible", inserted.getStatus());
        assertNull(inserted.getParentId());
        verify(contentMapper).incrCommentsCount(11L);

        assertEquals("article", view.targetType());
        assertEquals("好文", view.body());
        assertFalse(view.liked());
    }

    @Test
    void createReplyToReplyFlattensToTwoLevelCap() {
        login(moderator);
        when(guestbookMapper.selectById(13L)).thenReturn(entry(13L, "visible"));
        Comment root = comment(100L, CommentService.DB_GUESTBOOK, 13L, null, 2L);
        Comment sub = comment(200L, CommentService.DB_GUESTBOOK, 13L, 100L, 3L);
        when(commentMapper.selectById(200L)).thenReturn(sub);

        // 回复楼中楼（parent=200 的回复）：封顶挂到一级 100 下，冗余指向 200/作者 3
        CommentCreateRequest request = new CommentCreateRequest("guestbook", 13L, 200L, null, "sub-reply");
        service.create(request);

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentMapper).insert(captor.capture());
        Comment inserted = captor.getValue();
        assertEquals(100L, inserted.getParentId());
        assertEquals(200L, inserted.getReplyToCommentId());
        assertEquals(3L, inserted.getReplyToUserId());
    }

    @Test
    void createReplyToRootCommentKeepsItAsParent() {
        login(moderator);
        when(contentMapper.selectById(11L)).thenReturn(content(11L, "published"));
        Comment root = comment(100L, CommentService.DB_CONTENT, 11L, null, 2L);
        when(commentMapper.selectById(100L)).thenReturn(root);

        service.create(new CommentCreateRequest("article", 11L, null, 100L, "reply"));

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentMapper).insert(captor.capture());
        assertEquals(100L, captor.getValue().getParentId());
        assertEquals(100L, captor.getValue().getReplyToCommentId());
        assertEquals(2L, captor.getValue().getReplyToUserId());
    }

    @Test
    void createRejectsInvisibleReplyTarget() {
        login(moderator);
        when(contentMapper.selectById(11L)).thenReturn(content(11L, "published"));
        when(commentMapper.selectById(404L)).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service.create(new CommentCreateRequest("article", 11L, null, 404L, "reply")));
        assertEquals(ErrorCode.NOT_FOUND.code(), e.getErrorCode().code());
        assertEquals("被回复评论不存在", e.getMessage());
        verify(commentMapper, never()).insert(any(Comment.class));
    }

    @Test
    void threadSplitsRootsAndRepliesWithNamesAndLiked() {
        when(guestbookMapper.selectById(13L)).thenReturn(entry(13L, "visible"));
        stubUsers();
        Comment root1 = comment(100L, CommentService.DB_GUESTBOOK, 13L, null, 2L);
        Comment sub = comment(200L, CommentService.DB_GUESTBOOK, 13L, 100L, 3L);
        sub.setReplyToUserId(2L);
        Comment root2 = comment(101L, CommentService.DB_GUESTBOOK, 13L, null, 3L);
        when(commentMapper.selectList(any())).thenReturn(new java.util.ArrayList<>(List.of(root1, sub, root2)));
        CommentPraise liked = new CommentPraise();
        liked.setCommentId(100L);
        liked.setUserId(7L);
        when(commentPraiseMapper.selectList(any())).thenReturn(List.of(liked));
        login(moderator);

        CommentThread thread = service.thread("guestbook", 13L, moderator);

        assertEquals(2, thread.rootComments().size());
        assertEquals(1, thread.replyComments().size());
        CommentView root1View = thread.rootComments().get(0);
        assertEquals("alice", root1View.authorName());
        assertTrue(root1View.liked());
        CommentView subView = thread.replyComments().get(0);
        assertEquals("bob", subView.authorName());
        assertEquals("alice", subView.replyToUserName());
        assertFalse(subView.liked());

        // 游客视角：不查点赞表
        when(commentPraiseMapper.selectList(any())).thenReturn(List.of());
        CommentThread guestThread = service.thread("guestbook", 13L, null);
        assertFalse(guestThread.rootComments().get(0).liked());
    }

    @Test
    void deleteGuardsNotFoundDeletedAndNonAuthor() {
        BizException anonymous = assertThrows(BizException.class, () -> service.delete(100L));
        assertEquals(ErrorCode.UNAUTHENTICATED.code(), anonymous.getErrorCode().code());

        login(moderator);
        when(commentMapper.selectById(100L)).thenReturn(null);
        assertEquals("评论不存在", bizMessage(() -> service.delete(100L)));

        Comment deleted = comment(100L, CommentService.DB_CONTENT, 11L, null, 7L);
        deleted.setStatus("deleted");
        when(commentMapper.selectById(100L)).thenReturn(deleted);
        assertEquals("评论已删除", bizMessage(() -> service.delete(100L)));

        Comment foreign = comment(100L, CommentService.DB_CONTENT, 11L, null, 2L);
        when(commentMapper.selectById(100L)).thenReturn(foreign);
        AuthPrincipal nobody = new AuthPrincipal(9L, "guest", "viewer", false, Set.of());
        SecurityContextHolder.clearContext();
        login(nobody);
        assertEquals("评论不存在", bizMessage(() -> service.delete(100L)));
    }

    @Test
    void deleteModeratorRemovesRepliesAndDecrementsCount() {
        login(moderator);
        Comment root = comment(100L, CommentService.DB_CONTENT, 11L, null, 2L);
        when(commentMapper.selectById(100L)).thenReturn(root);
        Comment reply1 = comment(200L, CommentService.DB_CONTENT, 11L, 100L, 3L);
        Comment reply2 = comment(201L, CommentService.DB_CONTENT, 11L, 100L, 3L);
        when(commentMapper.selectList(any())).thenReturn(new java.util.ArrayList<>(List.of(reply1, reply2)));

        service.delete(100L);

        assertEquals("deleted", root.getStatus());
        assertEquals("deleted", reply1.getStatus());
        assertEquals("deleted", reply2.getStatus());
        verify(contentMapper).decrCommentsCount(11L, 3);
        verify(systemLogService).record(7L, "comment", "delete", "comment", 100L, null, null);
    }

    @Test
    void deleteAuthorOwnDiaryCommentSkipsCountRollback() {
        AuthPrincipal author = new AuthPrincipal(7L, "ed", "editor", false, Set.of());
        login(author);
        Comment root = comment(100L, CommentService.DB_DIARY, 12L, null, 7L);
        when(commentMapper.selectById(100L)).thenReturn(root);
        when(commentMapper.selectList(any())).thenReturn(new java.util.ArrayList<>(List.of()));

        service.delete(100L);

        assertEquals("deleted", root.getStatus());
        verify(contentMapper, never()).decrCommentsCount(anyLong(), eq(1));
        verify(systemLogService).record(eq(7L), eq("comment"), eq("delete"), eq("comment"), eq(100L),
                isNull(), isNull());
    }

    @Test
    void praiseRequiresVisibleComment() {
        login(moderator);
        when(commentMapper.selectById(100L)).thenReturn(null);
        assertEquals("评论不存在", bizMessage(() -> service.praise(100L)));

        Comment deleted = comment(100L, CommentService.DB_CONTENT, 11L, null, 2L);
        deleted.setStatus("deleted");
        when(commentMapper.selectById(100L)).thenReturn(deleted);
        assertEquals("评论不存在", bizMessage(() -> service.praise(100L)));
    }

    @Test
    void praiseToggleOnAndOff() {
        login(moderator);
        Comment comment = comment(100L, CommentService.DB_CONTENT, 11L, null, 2L);
        comment.setPraiseCount(5);
        when(commentMapper.selectById(100L)).thenReturn(comment);
        when(commentPraiseMapper.selectOne(any())).thenReturn(null);

        PraiseResult on = service.praise(100L);
        assertTrue(on.active());
        assertEquals(5, on.count());
        verify(commentPraiseMapper).insert(any(CommentPraise.class));
        verify(commentMapper).incrPraise(100L);

        when(commentPraiseMapper.selectOne(any()))
                .thenAnswer(inv -> {
                    CommentPraise p = new CommentPraise();
                    p.setCommentId(100L);
                    p.setUserId(7L);
                    return p;
                });
        PraiseResult off = service.praise(100L);
        assertFalse(off.active());
        verify(commentMapper).decrPraise(100L);
    }

    @Test
    void praiseSwallowsConcurrentDuplicateInsert() {
        login(moderator);
        Comment comment = comment(100L, CommentService.DB_CONTENT, 11L, null, 2L);
        when(commentMapper.selectById(100L)).thenReturn(comment);
        when(commentPraiseMapper.selectOne(any())).thenReturn(null);
        doThrow(new DuplicateKeyException("uk")).when(commentPraiseMapper).insert(any(CommentPraise.class));

        PraiseResult result = service.praise(100L);
        assertTrue(result.active());
    }

    @Test
    void toViewsHandlesNullPraiseCountAndEmptyInput() {
        assertTrue(service.toViews(null, "article", 7L).isEmpty());

        Comment plain = comment(100L, CommentService.DB_CONTENT, 11L, null, 2L);
        plain.setPraiseCount(null);
        plain.setAuthorId(null);
        when(userMapper.selectBatchIds(any())).thenReturn(List.of());
        when(commentPraiseMapper.selectList(any())).thenReturn(List.of());

        List<CommentView> views = service.toViews(List.of(plain), "article", 7L);
        assertEquals(1, views.size());
        assertEquals(0, views.get(0).praiseCount());
        assertNull(views.get(0).authorName());
    }

    @Test
    void toViewWrapsSingleComment() {
        Comment single = comment(100L, CommentService.DB_CONTENT, 11L, null, 2L);
        when(userMapper.selectBatchIds(any())).thenReturn(List.of());
        when(commentPraiseMapper.selectList(any())).thenReturn(List.of());

        CommentView view = service.toView(single, "article", null);
        assertEquals(100L, view.id());
        assertEquals("article", view.targetType());
        assertEquals("评论100", view.body());
        assertFalse(view.liked());
    }

    @Test
    void deleteUnknownAuthorWithoutModerateIs404() {
        AuthPrincipal viewer = new AuthPrincipal(9L, "guest", "viewer", false, Set.of());
        login(viewer);
        Comment orphan = comment(100L, CommentService.DB_CONTENT, 11L, null, null);
        when(commentMapper.selectById(100L)).thenReturn(orphan);
        assertEquals("评论不存在", bizMessage(() -> service.delete(100L)));
    }

    @Test
    void praiseCountFallsBackToZeroWhenLatestMissing() {
        login(moderator);
        Comment comment = comment(100L, CommentService.DB_CONTENT, 11L, null, 2L);
        when(commentMapper.selectById(100L)).thenReturn(comment, (Comment) null);
        when(commentPraiseMapper.selectOne(any())).thenReturn(null);

        PraiseResult result = service.praise(100L);
        assertTrue(result.active());
        assertEquals(0, result.count());
    }
}
