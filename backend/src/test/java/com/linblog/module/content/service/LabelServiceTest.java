package com.linblog.module.content.service;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.content.dto.LabelRequest;
import com.linblog.module.content.entity.Label;
import com.linblog.module.content.mapper.ContentLabelMapper;
import com.linblog.module.content.mapper.LabelMapper;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 标签管理（§5.3）：label.manage；重复 40900；引用未清 40900。 */
class LabelServiceTest {

    private LabelMapper labelMapper;
    private ContentLabelMapper contentLabelMapper;
    private SystemLogService systemLogService;
    private LabelService service;

    private static final AuthPrincipal MANAGER =
            new AuthPrincipal(1L, "root", "admin", false, Set.of("label.manage"));

    @BeforeEach
    void setUp() {
        labelMapper = mock(LabelMapper.class);
        contentLabelMapper = mock(ContentLabelMapper.class);
        systemLogService = mock(SystemLogService.class);
        service = new LabelService(labelMapper, contentLabelMapper, systemLogService);
        loginAs(MANAGER);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(AuthPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, null));
    }

    @Test
    void createTrimsNameAndLogs() {
        doAnswer(inv -> {
            Label l = inv.getArgument(0);
            l.setId(9L);
            return 1;
        }).when(labelMapper).insert(any(Label.class));

        Label label = service.create(new LabelRequest("  Java  "));

        assertEquals("Java", label.getName());
        assertEquals(9L, label.getId());
        verify(systemLogService).record(1L, "label", "create", "label", 9L, "Java", null);
    }

    @Test
    void createDuplicateConflict() {
        doThrow(new DuplicateKeyException("dup")).when(labelMapper).insert(any(Label.class));
        BizException ex = assertThrows(BizException.class, () -> service.create(new LabelRequest("x")));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertEquals("标签已存在", ex.getMessage());
    }

    @Test
    void writeRequiresLabelManage() {
        loginAs(new AuthPrincipal(2L, "ed", "viewer", false, Set.of()));
        BizException ex = assertThrows(BizException.class, () -> service.create(new LabelRequest("x")));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());

        SecurityContextHolder.clearContext();
        assertThrows(BizException.class, () -> service.create(new LabelRequest("x")));
    }

    @Test
    void updateNotFoundAndConflict() {
        when(labelMapper.selectById(404L)).thenReturn(null);
        assertThrows(BizException.class, () -> service.update(404L, new LabelRequest("n")));

        Label label = new Label();
        label.setId(5L);
        label.setName("old");
        when(labelMapper.selectById(5L)).thenReturn(label);
        doThrow(new DuplicateKeyException("dup")).when(labelMapper).updateById(any(Label.class));
        BizException ex = assertThrows(BizException.class, () -> service.update(5L, new LabelRequest("n")));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
    }

    @Test
    void deleteReferencedConflict() {
        Label label = new Label();
        label.setId(5L);
        label.setName("used");
        when(labelMapper.selectById(5L)).thenReturn(label);
        when(contentLabelMapper.selectCount(any())).thenReturn(2L);

        BizException ex = assertThrows(BizException.class, () -> service.delete(5L));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertEquals("标签仍被内容引用，无法删除", ex.getMessage());
        verify(labelMapper, never()).deleteById(5L);
    }

    @Test
    void deleteSuccessRemovesRowAndLogs() {
        Label label = new Label();
        label.setId(6L);
        label.setName("free");
        when(labelMapper.selectById(6L)).thenReturn(label);
        when(contentLabelMapper.selectCount(any())).thenReturn(0L);

        service.delete(6L);

        verify(labelMapper).deleteById(6L);
        verify(systemLogService).record(1L, "label", "delete", "label", 6L, "free", null);
    }

    @Test
    void listAllReturnsAll() {
        when(labelMapper.selectList(any())).thenReturn(List.of(new Label()));
        assertEquals(1, service.listAll().size());
    }
}
