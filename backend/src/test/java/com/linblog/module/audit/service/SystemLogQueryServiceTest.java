package com.linblog.module.audit.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.audit.dto.SystemLogView;
import com.linblog.module.audit.entity.SystemLog;
import com.linblog.module.audit.mapper.SystemLogMapper;
import com.linblog.module.user.entity.User;
import com.linblog.module.user.mapper.UserMapper;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** §5.8 系统日志查询：systemLog.view 门禁、module/action 筛选、操作人批查。 */
class SystemLogQueryServiceTest {

    private SystemLogMapper systemLogMapper;
    private UserMapper userMapper;
    private SystemLogQueryService service;

    private static final AuthPrincipal VIEWER = new AuthPrincipal(1L, "root", "super_admin", true,
            Set.of("systemLog.view"));
    private static final AuthPrincipal NO_PERM = new AuthPrincipal(2L, "ed", "editor", false, Set.of());

    private static SystemLog log(long id, Long userId, String module, String action) {
        SystemLog l = new SystemLog();
        l.setId(id);
        l.setUserId(userId);
        l.setModule(module);
        l.setAction(action);
        l.setTargetType("user");
        l.setTargetId(id);
        l.setDetail("detail-" + id);
        l.setIp("1.1.1.1");
        l.setCreatedAt(LocalDateTime.now());
        return l;
    }

    @BeforeEach
    void setUp() {
        systemLogMapper = mock(SystemLogMapper.class);
        userMapper = mock(UserMapper.class);
        service = new SystemLogQueryService(systemLogMapper, userMapper);
        when(userMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void anonymousRejected() {
        BizException ex = assertThrows(BizException.class,
                () -> service.page(new PageQuery(), null, null, null));
        assertEquals(ErrorCode.UNAUTHENTICATED, ex.getErrorCode());
    }

    @Test
    void missingPermissionRejected() {
        BizException ex = assertThrows(BizException.class,
                () -> service.page(new PageQuery(), null, null, NO_PERM));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void pageResolvesOperatorNames() {
        User alice = new User();
        alice.setId(7L);
        alice.setUsername("alice");
        when(userMapper.selectList(any())).thenReturn(List.of(alice));
        doAnswer(inv -> {
            Page<SystemLog> p = inv.getArgument(0);
            p.setRecords(List.of(log(11L, 7L, "auth", "register"), log(12L, null, "mail", "send_failed")));
            p.setTotal(2);
            return p;
        }).when(systemLogMapper).selectPage(any(), any());

        PageResult<SystemLogView> result = service.page(new PageQuery(), null, null, VIEWER);

        assertEquals(2, result.total());
        assertEquals("alice", result.records().get(0).userName());
        assertNull(result.records().get(1).userName());
        assertNull(result.records().get(1).userId());
    }

    @Test
    void pageWithoutUsersSkipsNameLookup() {
        doAnswer(inv -> {
            Page<SystemLog> p = inv.getArgument(0);
            p.setRecords(List.of(log(11L, null, "file", "purge")));
            p.setTotal(1);
            return p;
        }).when(systemLogMapper).selectPage(any(), any());

        PageResult<SystemLogView> result = service.page(new PageQuery(), "file", "purge", VIEWER);
        assertEquals(1, result.records().size());
        verify(userMapper, never()).selectList(any());
    }

    @Test
    void emptyPageReturnsEmptyResult() {
        doAnswer(inv -> {
            Page<SystemLog> p = inv.getArgument(0);
            p.setRecords(List.of());
            p.setTotal(0);
            return p;
        }).when(systemLogMapper).selectPage(any(), any());

        PageResult<SystemLogView> result = service.page(new PageQuery(), null, null, VIEWER);
        assertEquals(0, result.records().size());
        assertEquals(0, result.total());
    }
}
