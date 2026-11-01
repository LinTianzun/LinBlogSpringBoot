package com.linblog.module.content.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.content.dto.ContentCreateRequest;
import com.linblog.module.content.dto.ContentStateRequest;
import com.linblog.module.content.dto.ContentUpdateRequest;
import com.linblog.module.content.dto.ContentView;
import com.linblog.module.content.entity.Content;
import com.linblog.module.content.entity.ContentLabel;
import com.linblog.module.content.entity.Label;
import com.linblog.module.content.mapper.ContentLabelMapper;
import com.linblog.module.content.mapper.ContentMapper;
import com.linblog.module.content.mapper.LabelMapper;
import com.linblog.module.media.entity.Media;
import com.linblog.module.media.mapper.MediaMapper;
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
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 内容域（§5.3/§7.3）：状态机、发布权限、回收站、scope 与防探测。 */
class ContentServiceTest {

    private ContentMapper contentMapper;
    private ContentLabelMapper contentLabelMapper;
    private LabelMapper labelMapper;
    private MediaMapper mediaMapper;
    private UserMapper userMapper;
    private ContentJsonValidator jsonValidator;
    private SystemLogService systemLogService;
    private ContentService service;

    private static final String TIPTAP =
            "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"正文\"}]}]}";

    private static AuthPrincipal principalOf(Set<String> keys) {
        return new AuthPrincipal(7L, "ed", "editor", false, keys);
    }

    private static Content content(long id, String type, String state, long authorId) {
        Content c = new Content();
        c.setId(id);
        c.setAuthorId(authorId);
        c.setContentType(type);
        c.setTitle("标题" + id);
        c.setState(state);
        c.setViews(0);
        c.setPraises(0);
        c.setCommentsCount(0);
        c.setIsPinned(false);
        return c;
    }

    @BeforeEach
    void setUp() {
        // MP 3.5.x lambda 列解析是惰性的（getSqlSegment 时才触发），
        // 需要提前注册 TableInfo 才能在单测里断言 wrapper 的 SQL 片段
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), Content.class);
        contentMapper = mock(ContentMapper.class);
        contentLabelMapper = mock(ContentLabelMapper.class);
        labelMapper = mock(LabelMapper.class);
        mediaMapper = mock(MediaMapper.class);
        userMapper = mock(UserMapper.class);
        jsonValidator = new ContentJsonValidator(new ObjectMapper());
        systemLogService = mock(SystemLogService.class);
        service = new ContentService(contentMapper, contentLabelMapper, labelMapper,
                mediaMapper, userMapper, jsonValidator, systemLogService);
        // toViews 依赖：作者与标签关联均为空集
        when(userMapper.selectList(any())).thenReturn(List.of());
        when(contentLabelMapper.selectList(any())).thenReturn(List.of());
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

    // ---------- create ----------

    @Test
    void createArticleDraftExtractsPlainTextAndSavesLabels() {
        loginAs(principalOf(Set.of("article.create", "article.manageOwn")));
        doAnswer(inv -> {
            Content c = inv.getArgument(0);
            c.setId(11L);
            return 1;
        }).when(contentMapper).insert(any(Content.class));
        when(labelMapper.selectById(1L)).thenReturn(new Label());
        when(labelMapper.selectById(2L)).thenReturn(null);

        ContentView view = service.create(new ContentCreateRequest(
                "article", "  我的第一篇  ", "摘要", TIPTAP, null, List.of(1L, 2L, 1L), null));

        assertEquals("我的第一篇", view.title());
        assertEquals("draft", view.state());
        assertEquals("正文", view.plainText());
        assertNull(view.publishedAt());
        verify(contentLabelMapper).deleteByContentId(11L);
        // labelIds [1,2,1]：去重为 [1,2]，label2 不存在被过滤，仅 label1 落库
        ArgumentCaptor<ContentLabel> labelCaptor = ArgumentCaptor.forClass(ContentLabel.class);
        verify(contentLabelMapper).insert(labelCaptor.capture());
        assertEquals(1L, labelCaptor.getValue().getLabelId());
        verify(systemLogService).record(7L, "content", "create", "article", 11L, "我的第一篇", null);
    }

    @Test
    void createPublishedArticleRequiresPublishKey() {
        loginAs(principalOf(Set.of("article.create")));
        BizException ex = assertThrows(BizException.class, () -> service.create(
                new ContentCreateRequest("article", "t", null, TIPTAP, null, null, "published")));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());

        loginAs(principalOf(Set.of("article.create", "article.publish")));
        doAnswer(inv -> {
            Content c = inv.getArgument(0);
            c.setId(12L);
            return 1;
        }).when(contentMapper).insert(any(Content.class));
        ContentView view = service.create(
                new ContentCreateRequest("article", "t", null, TIPTAP, null, null, "published"));
        assertEquals("published", view.state());
        assertNotNull(view.publishedAt());
    }

    @Test
    void createImageRequiresCoverAndSkipsBodyValidation() {
        loginAs(principalOf(Set.of("gallery.create")));
        BizException ex = assertThrows(BizException.class, () -> service.create(
                new ContentCreateRequest("image", "图注", null, "not-json", null, null, null)));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertEquals("图片内容必须指定封面", ex.getMessage());

        ContentView view = service.create(
                new ContentCreateRequest("image", "图注", null, null, 33L, null, null));
        assertEquals("image", view.contentType());
        assertNull(view.body());
    }

    @Test
    void createValidationErrors() {
        loginAs(principalOf(Set.of("article.create")));
        assertThrows(BizException.class, () -> service.create(
                new ContentCreateRequest("video", "t", null, null, null, null, null)));
        assertThrows(BizException.class, () -> service.create(
                new ContentCreateRequest("article", "  ", null, null, null, null, null)));
        assertThrows(BizException.class, () -> service.create(
                new ContentCreateRequest("article", "t".repeat(151), null, null, null, null, null)));
        assertThrows(BizException.class, () -> service.create(
                new ContentCreateRequest("article", "t", "x".repeat(513), null, null, null, null)));
        assertThrows(BizException.class, () -> service.create(
                new ContentCreateRequest("article", "t", null, null, null, null, "hidden")));
    }

    @Test
    void createRequiresAuthentication() {
        assertThrows(BizException.class, () -> service.create(
                new ContentCreateRequest("article", "t", null, TIPTAP, null, null, null)));
    }

    // ---------- changeState ----------

    @Test
    void changeStatePublishFlowWritesPublishedAtOnce() {
        AuthPrincipal editor = principalOf(Set.of("article.manageAll", "article.publish"));
        loginAs(editor);
        Content c = content(20L, "article", "draft", 7L);
        when(contentMapper.selectById(20L)).thenReturn(c);

        service.changeState(20L, new ContentStateRequest("published"));
        assertEquals("published", c.getState());
        assertNotNull(c.getPublishedAt());

        // published → published：无变化不重写时间
        LocalDateTime at = c.getPublishedAt();
        service.changeState(20L, new ContentStateRequest("published"));
        assertEquals(at, c.getPublishedAt());
    }

    @Test
    void changeStatePublishWithoutKeyForbidden() {
        loginAs(principalOf(Set.of("article.manageAll")));
        when(contentMapper.selectById(21L)).thenReturn(content(21L, "article", "draft", 7L));
        assertThrows(BizException.class,
                () -> service.changeState(21L, new ContentStateRequest("published")));

        // 进入 published 需键；published → hidden 同样需要
        Content published = content(21L, "article", "published", 7L);
        when(contentMapper.selectById(21L)).thenReturn(published);
        assertThrows(BizException.class,
                () -> service.changeState(21L, new ContentStateRequest("hidden")));
    }

    @Test
    void changeStateTrashRestoreAndLimits() {
        loginAs(principalOf(Set.of("article.manageAll")));
        Content trashed = content(22L, "article", "deleted", 7L);
        when(contentMapper.selectById(22L)).thenReturn(trashed);

        service.changeState(22L, new ContentStateRequest("draft"));
        assertEquals("draft", trashed.getState());

        // 回收站内容不可直接发布
        Content trashed2 = content(23L, "article", "deleted", 7L);
        when(contentMapper.selectById(23L)).thenReturn(trashed2);
        assertThrows(BizException.class,
                () -> service.changeState(23L, new ContentStateRequest("published")));

        // 非法目标
        assertThrows(BizException.class,
                () -> service.changeState(22L, new ContentStateRequest("deleted")));
    }

    // ---------- delete / purge ----------

    @Test
    void deleteTrashesContentAndUnreferencedCover() {
        loginAs(principalOf(Set.of("article.manageAll")));
        Content c = content(30L, "article", "published", 7L);
        c.setCoverMediaId(99L);
        when(contentMapper.selectById(30L)).thenReturn(c);
        when(mediaMapper.countCoverReferences(99L, 30L)).thenReturn(0L);
        Media cover = new Media();
        cover.setId(99L);
        cover.setState("active");
        when(mediaMapper.selectById(99L)).thenReturn(cover);

        service.delete(30L);

        assertEquals("deleted", c.getState());
        assertEquals("trash", cover.getState());
        assertNotNull(cover.getTrashedAt());
        verify(mediaMapper).updateById(cover);
        verify(systemLogService).record(7L, "content", "trash", "article", 30L, "标题30", null);
    }

    @Test
    void deleteKeepsReferencedCover() {
        loginAs(principalOf(Set.of("article.manageAll")));
        Content c = content(31L, "article", "draft", 7L);
        c.setCoverMediaId(99L);
        when(contentMapper.selectById(31L)).thenReturn(c);
        when(mediaMapper.countCoverReferences(99L, 31L)).thenReturn(2L);

        service.delete(31L);
        assertEquals("deleted", c.getState());
        verify(mediaMapper, never()).updateById(any(Media.class));
    }

    @Test
    void purgeRequiresManageAllAndTrashedState() {
        loginAs(principalOf(Set.of("article.manageOwn")));
        when(contentMapper.selectById(40L)).thenReturn(content(40L, "article", "deleted", 7L));
        BizException ex = assertThrows(BizException.class, () -> service.purge(40L));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());

        loginAs(principalOf(Set.of("article.manageAll")));
        when(contentMapper.selectById(41L)).thenReturn(content(41L, "article", "draft", 7L));
        BizException ex2 = assertThrows(BizException.class, () -> service.purge(41L));
        assertEquals(ErrorCode.BAD_REQUEST, ex2.getErrorCode());

        when(contentMapper.selectById(42L)).thenReturn(content(42L, "article", "deleted", 7L));
        service.purge(42L);
        verify(contentLabelMapper).deleteByContentId(42L);
        verify(contentMapper).deleteById(42L);
    }

    // ---------- adminGet / adminPage ----------

    @Test
    void adminGetScopeAndAntiProbe() {
        loginAs(principalOf(Set.of("article.viewAll")));
        when(contentMapper.selectById(50L)).thenReturn(content(50L, "article", "draft", 8L));
        assertEquals(50L, service.adminGet(50L, principalOf(Set.of("article.viewAll"))).id());

        // viewOwn 看别人的 → 40400（防探测）
        AuthPrincipal owner = principalOf(Set.of("article.viewOwn"));
        loginAs(owner);
        assertThrows(BizException.class, () -> service.adminGet(50L, owner));

        // 自己的 → 可见
        when(contentMapper.selectById(51L)).thenReturn(content(51L, "article", "draft", 7L));
        assertEquals(51L, service.adminGet(51L, owner).id());

        // 无任何权限 → 40400
        AuthPrincipal nobody = principalOf(Set.of());
        assertThrows(BizException.class, () -> service.adminGet(50L, nobody));
    }

    @Test
    void adminPageMapsBriefViews() {
        loginAs(principalOf(Set.of("article.viewAll")));
        Content row = content(60L, "article", "published", 7L);
        row.setBody(TIPTAP);
        row.setPlainText("正文");
        // MP 分页语义：selectPage 填充传入的 Page 对象，service 读取的是自身 Page
        doAnswer(inv -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<Content> p = inv.getArgument(0);
            p.setRecords(List.of(row));
            p.setTotal(1);
            return p;
        }).when(contentMapper).selectPage(any(), any());

        PageQuery query = new PageQuery();
        PageResult<ContentView> result = service.adminPage(query, "article", null, null, true, 7L);
        assertEquals(1, result.total());
        assertEquals(1, result.records().size());
        assertNull(result.records().get(0).body(), "列表不带正文大字段");
    }

    // ---------- portal ----------

    @Test
    void portalGetPublishedOnlyWithPreviewRules() {
        when(contentMapper.selectById(70L)).thenReturn(content(70L, "article", "published", 9L));
        assertEquals(70L, service.portalGet(70L, false, null).id());

        // 未发布：匿名/无 preview → 40400（stranger 用不同 uid，避免与作者重合）
        when(contentMapper.selectById(71L)).thenReturn(content(71L, "article", "draft", 7L));
        assertThrows(BizException.class, () -> service.portalGet(71L, false, null));
        AuthPrincipal stranger = new AuthPrincipal(8L, "stranger", "editor", false,
                Set.of("article.viewAll"));
        assertThrows(BizException.class, () -> service.portalGet(71L, true, stranger));

        // 作者本人 preview 可看
        AuthPrincipal author = principalOf(Set.of("article.viewOwn"));
        assertEquals(71L, service.portalGet(71L, true, author).id());
        // manageAll preview 可看
        AuthPrincipal manager = principalOf(Set.of("article.manageAll"));
        assertEquals(71L, service.portalGet(71L, true, manager).id());
    }

    @Test
    void portalPageForcesPublishedState() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Content> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<Content>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        when(contentMapper.selectPage(any(), captor.capture())).thenReturn(page);

        service.portalPage(new PageQuery(), "article", null);
        assertTrue(captor.getValue().getSqlSegment().contains("state"), "门户查询强制 state 条件");
    }

    @Test
    void updateOwnershipAndFields() {
        AuthPrincipal owner = principalOf(Set.of("article.manageOwn"));
        loginAs(owner);
        when(contentMapper.selectById(80L)).thenReturn(content(80L, "article", "draft", 7L));
        when(contentMapper.selectById(81L)).thenReturn(content(81L, "article", "draft", 9L));

        assertThrows(BizException.class, () -> service.update(81L,
                new ContentUpdateRequest("改", null, null, null, null, null)));

        ContentView view = service.update(80L, new ContentUpdateRequest(
                "  改标题  ", "新摘要", TIPTAP, 5L, List.of(1L), true));
        assertEquals("改标题", view.title());
        assertTrue(view.isPinned());
        verify(contentLabelMapper).deleteByContentId(80L);
        verify(contentMapper).updateById(any(Content.class));
    }

    @Test
    void purgeExpiredPhysicallyRemoves() {
        Content expired = content(90L, "article", "deleted", 7L);
        when(contentMapper.selectList(any())).thenReturn(List.of(expired));
        int n = service.purgeExpired(LocalDateTime.now().minusDays(30));
        assertEquals(1, n);
        verify(contentLabelMapper).deleteByContentId(90L);
        verify(contentMapper).deleteById(90L);
    }

    @Test
    void incrPraiseUnusedHereButMapperWired() {
        // incrViews/incrPraises 由 ViewService 使用，这里仅确认 mapper 注入无冲突
        when(contentMapper.selectById(anyLong())).thenReturn(null);
        assertThrows(BizException.class, () -> service.adminGet(1L, principalOf(Set.of("article.viewAll"))));
    }
}
