package com.linblog.module.diary.service;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.diary.dto.DiaryCreateRequest;
import com.linblog.module.diary.dto.DiaryUpdateRequest;
import com.linblog.module.diary.dto.DiaryView;
import com.linblog.module.diary.entity.Diary;
import com.linblog.module.diary.mapper.DiaryMapper;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 日记（§5.4）：weather 白名单、一天一篇 40900、归属与门户 published。 */
class DiaryServiceTest {

    private DiaryMapper diaryMapper;
    private SystemLogService systemLogService;
    private DiaryService service;

    private static AuthPrincipal principalOf(Set<String> keys) {
        return new AuthPrincipal(7L, "ed", "editor", false, keys);
    }

    private static Diary diary(long id, long authorId, String state) {
        Diary d = new Diary();
        d.setId(id);
        d.setAuthorId(authorId);
        d.setDiaryDate(LocalDate.of(2026, 5, 1));
        d.setWeather("晴天");
        d.setBody("{\"type\":\"doc\"}");
        d.setState(state);
        d.setViews(0);
        d.setPraises(0);
        return d;
    }

    @BeforeEach
    void setUp() {
        diaryMapper = mock(DiaryMapper.class);
        systemLogService = mock(SystemLogService.class);
        service = new DiaryService(diaryMapper, systemLogService);
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
    void createValidatesWeatherAndDefaultsDraft() {
        loginAs(principalOf(Set.of("diary.create")));
        doAnswer(inv -> {
            Diary d = inv.getArgument(0);
            d.setId(3L);
            return 1;
        }).when(diaryMapper).insert(any(Diary.class));

        DiaryView view = service.create(new DiaryCreateRequest(
                LocalDate.of(2026, 5, 1), "多云", "平静", "{\"type\":\"doc\"}", null));
        assertEquals("draft", view.state());
        assertNull(view.authorName(), "作者名填充留 M6");
        verify(systemLogService).record(7L, "diary", "create", "diary", 3L, null, null);
    }

    @Test
    void createRejectsBadWeatherAndAnonymousAndNoKey() {
        loginAs(principalOf(Set.of("diary.create")));
        BizException ex = assertThrows(BizException.class, () -> service.create(
                new DiaryCreateRequest(LocalDate.of(2026, 5, 1), "台风", null, null, null)));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertEquals("天气取值不合法", ex.getMessage());

        SecurityContextHolder.clearContext();
        assertThrows(BizException.class, () -> service.create(
                new DiaryCreateRequest(LocalDate.of(2026, 5, 1), "晴天", null, null, null)));

        loginAs(principalOf(Set.of("diary.viewAll")));
        assertThrows(BizException.class, () -> service.create(
                new DiaryCreateRequest(LocalDate.of(2026, 5, 1), "晴天", null, null, null)));
    }

    @Test
    void createDuplicateDateConflict() {
        loginAs(principalOf(Set.of("diary.create")));
        doThrow(new DuplicateKeyException("dup")).when(diaryMapper).insert(any(Diary.class));
        BizException ex = assertThrows(BizException.class, () -> service.create(
                new DiaryCreateRequest(LocalDate.of(2026, 5, 1), "晴天", null, null, null)));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertEquals("该日期已有日记", ex.getMessage());
    }

    @Test
    void createRejectsInvalidState() {
        loginAs(principalOf(Set.of("diary.create")));
        assertThrows(BizException.class, () -> service.create(
                new DiaryCreateRequest(LocalDate.of(2026, 5, 1), "晴天", null, null, "hidden")));
    }

    @Test
    void pageFiltersOwnershipAndState() {
        // MP 分页语义：selectPage 填充传入的 Page 对象，service 读取的是自身 Page
        doAnswer(inv -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<Diary> p = inv.getArgument(0);
            p.setRecords(List.of(diary(1L, 7L, "published")));
            p.setTotal(1);
            return p;
        }).when(diaryMapper).selectPage(any(), any());

        PageResult<DiaryView> all = service.page(new PageQuery(), null, true, 7L);
        assertEquals(1, all.total());
        PageResult<DiaryView> own = service.page(new PageQuery(), "published", false, 7L);
        assertEquals(1, own.total());
    }

    @Test
    void getScopeChecks() {
        when(diaryMapper.selectById(9L)).thenReturn(diary(9L, 9L, "draft"));
        assertThrows(BizException.class, () -> service.get(9L, false, 7L));
        when(diaryMapper.selectById(10L)).thenReturn(diary(10L, 7L, "draft"));
        assertEquals(10L, service.get(10L, false, 7L).id());
        assertEquals(9L, service.get(9L, true, 7L).id());
    }

    @Test
    void portalGetOnlyPublished() {
        when(diaryMapper.selectById(20L)).thenReturn(diary(20L, 7L, "published"));
        assertEquals(20L, service.portalGet(20L).id());
        when(diaryMapper.selectById(21L)).thenReturn(diary(21L, 7L, "draft"));
        assertThrows(BizException.class, () -> service.portalGet(21L));
    }

    @Test
    void updateValidatesWeatherAndStateOwnership() {
        AuthPrincipal stranger = principalOf(Set.of("diary.manageOwn"));
        loginAs(stranger);
        when(diaryMapper.selectById(30L)).thenReturn(diary(30L, 9L, "draft"));
        assertThrows(BizException.class, () -> service.update(30L,
                new DiaryUpdateRequest(null, "小雨", null, null, null)));

        AuthPrincipal manager = principalOf(Set.of("diary.manageAll"));
        loginAs(manager);
        when(diaryMapper.selectById(31L)).thenReturn(diary(31L, 9L, "draft"));
        BizException badWeather = assertThrows(BizException.class, () -> service.update(31L,
                new DiaryUpdateRequest(null, "台风", null, null, null)));
        assertEquals("天气取值不合法", badWeather.getMessage());
        assertThrows(BizException.class, () -> service.update(31L,
                new DiaryUpdateRequest(null, null, null, null, "hidden")));

        service.update(31L, new DiaryUpdateRequest(LocalDate.of(2026, 6, 1), "雷雨", "忙", null, "published"));
        verify(diaryMapper).updateById(any(Diary.class));
    }

    @Test
    void updateDuplicateDateConflict() {
        loginAs(principalOf(Set.of("diary.manageAll")));
        when(diaryMapper.selectById(32L)).thenReturn(diary(32L, 9L, "draft"));
        doThrow(new DuplicateKeyException("dup")).when(diaryMapper).updateById(any(Diary.class));
        BizException ex = assertThrows(BizException.class, () -> service.update(32L,
                new DiaryUpdateRequest(LocalDate.of(2026, 7, 1), null, null, null, null)));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
    }

    @Test
    void deleteRequiresOwnership() {
        AuthPrincipal owner = principalOf(Set.of("diary.manageOwn"));
        loginAs(owner);
        when(diaryMapper.selectById(40L)).thenReturn(diary(40L, 9L, "draft"));
        assertThrows(BizException.class, () -> service.delete(40L));

        when(diaryMapper.selectById(41L)).thenReturn(diary(41L, 7L, "draft"));
        service.delete(41L);
        verify(diaryMapper).deleteById(41L);
    }
}
