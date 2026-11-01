package com.linblog.module.comment.service;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.comment.dto.InteractionRequest;
import com.linblog.module.comment.dto.PraiseResult;
import com.linblog.module.comment.entity.Interaction;
import com.linblog.module.comment.mapper.InteractionMapper;
import com.linblog.module.content.entity.Content;
import com.linblog.module.content.mapper.ContentMapper;
import com.linblog.module.diary.entity.Diary;
import com.linblog.module.diary.mapper.DiaryMapper;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 内容/日记点赞（§5.5）：toggle 幂等、uk 兜底、只允许已发布目标、计数回显。 */
class InteractionServiceTest {

    private InteractionMapper interactionMapper;
    private ContentMapper contentMapper;
    private DiaryMapper diaryMapper;
    private InteractionService service;

    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        registerTableInfo(Interaction.class);
        interactionMapper = mock(InteractionMapper.class);
        contentMapper = mock(ContentMapper.class);
        diaryMapper = mock(DiaryMapper.class);
        service = new InteractionService(interactionMapper, contentMapper, diaryMapper);
        principal = new AuthPrincipal(7L, "ed", "editor", false, Set.of());
        login(principal);
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

    private void login(AuthPrincipal value) {
        SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.authentication
                        .UsernamePasswordAuthenticationToken(value, null, List.of()));
    }

    private Content content(long id, String state, Integer praises) {
        Content c = new Content();
        c.setId(id);
        c.setState(state);
        c.setPraises(praises);
        return c;
    }

    private Diary diary(long id, String state, Integer praises) {
        Diary d = new Diary();
        d.setId(id);
        d.setState(state);
        d.setPraises(praises);
        return d;
    }

    private String bizMessage(InteractionRequest request) {
        BizException e = assertThrows(BizException.class, () -> service.toggle(request));
        return e.getMessage();
    }

    @Test
    void toggleRequiresLogin() {
        SecurityContextHolder.clearContext();
        BizException e = assertThrows(BizException.class,
                () -> service.toggle(new InteractionRequest("content", 11L)));
        assertEquals(ErrorCode.UNAUTHENTICATED.code(), e.getErrorCode().code());
        assertEquals("未登录", e.getMessage());
    }

    @Test
    void toggleValidatesTargetTypeAndId() {
        assertEquals("点赞目标类型不合法", bizMessage(new InteractionRequest(null, 11L)));
        assertEquals("点赞目标类型不合法", bizMessage(new InteractionRequest("video", 11L)));
        assertEquals("点赞目标类型不合法", bizMessage(new InteractionRequest("guestbook", 11L)));
        assertEquals("点赞目标不能为空", bizMessage(new InteractionRequest("content", null)));
        verify(contentMapper, never()).selectById(any());
    }

    @Test
    void toggleRejectsUnpublishedContent() {
        when(contentMapper.selectById(11L)).thenReturn(null);
        assertEquals("内容不存在", bizMessage(new InteractionRequest("content", 11L)));

        when(contentMapper.selectById(11L)).thenReturn(content(11L, "draft", 0));
        assertEquals("内容不存在", bizMessage(new InteractionRequest("content", 11L)));
    }

    @Test
    void toggleRejectsUnpublishedDiary() {
        when(diaryMapper.selectById(12L)).thenReturn(null);
        assertEquals("日记不存在", bizMessage(new InteractionRequest("diary", 12L)));

        when(diaryMapper.selectById(12L)).thenReturn(diary(12L, "draft", 0));
        assertEquals("日记不存在", bizMessage(new InteractionRequest("diary", 12L)));
    }

    @Test
    void toggleOnInsertsIncrementsAndEchoesCount() {
        when(contentMapper.selectById(11L))
                .thenReturn(content(11L, "published", 5)); // 校验与计数回显共用同一 mock
        when(interactionMapper.selectOne(any())).thenReturn(null);

        PraiseResult result = service.toggle(new InteractionRequest("  content  ", 11L));

        assertTrue(result.active());
        assertEquals(5, result.count());
        ArgumentCaptor<Interaction> captor = ArgumentCaptor.forClass(Interaction.class);
        verify(interactionMapper).insert(captor.capture());
        Interaction inserted = captor.getValue();
        assertEquals("content", inserted.getTargetType());
        assertEquals(11L, inserted.getTargetId());
        assertEquals(7L, inserted.getUserId());
        assertNotNull(inserted.getCreatedAt());
        verify(contentMapper).incrPraises(11L);
        verify(contentMapper, never()).decrPraises(11L);
    }

    @Test
    void toggleOffDeletesDecrementsAndEchoesCount() {
        when(contentMapper.selectById(11L)).thenReturn(content(11L, "published", 5));
        Interaction existing = new Interaction();
        existing.setId(9L);
        existing.setTargetType("content");
        existing.setTargetId(11L);
        existing.setUserId(7L);
        when(interactionMapper.selectOne(any())).thenReturn(existing);

        PraiseResult result = service.toggle(new InteractionRequest("content", 11L));

        assertFalse(result.active());
        assertEquals(5, result.count());
        verify(interactionMapper).deleteById(9L);
        verify(contentMapper).decrPraises(11L);
        verify(interactionMapper, never()).insert(any(Interaction.class));
    }

    @Test
    void toggleOnDiaryUsesDiaryMappers() {
        when(diaryMapper.selectById(12L)).thenReturn(diary(12L, "published", 2));
        when(interactionMapper.selectOne(any())).thenReturn(null);

        PraiseResult result = service.toggle(new InteractionRequest("diary", 12L));

        assertTrue(result.active());
        assertEquals(2, result.count());
        verify(diaryMapper).incrPraises(12L);
        verify(contentMapper, never()).incrPraises(any());
    }

    @Test
    void toggleSwallowsConcurrentDuplicateInsert() {
        when(contentMapper.selectById(11L)).thenReturn(content(11L, "published", 1));
        when(interactionMapper.selectOne(any())).thenReturn(null);
        doThrow(new DuplicateKeyException("uk_target_user")).when(interactionMapper).insert(any(Interaction.class));

        PraiseResult result = service.toggle(new InteractionRequest("content", 11L));
        assertTrue(result.active());
        verify(contentMapper).incrPraises(11L);
    }

    @Test
    void toggleEchoesZeroWhenCountMissing() {
        when(contentMapper.selectById(11L)).thenReturn(content(11L, "published", null));
        when(interactionMapper.selectOne(any())).thenReturn(null);

        PraiseResult result = service.toggle(new InteractionRequest("content", 11L));
        assertTrue(result.active());
        assertEquals(0, result.count());
    }
}
