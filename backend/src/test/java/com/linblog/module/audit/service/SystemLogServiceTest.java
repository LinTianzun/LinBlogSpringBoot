package com.linblog.module.audit.service;

import com.linblog.module.audit.entity.SystemLog;
import com.linblog.module.audit.mapper.SystemLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SystemLogServiceTest {

    private SystemLogMapper mapper;
    private SystemLogService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SystemLogMapper.class);
        service = new SystemLogService(mapper);
    }

    @Test
    void recordMapsFieldsAndTruncatesDetail() {
        service.record(9L, "auth", "register", "user", 9L, "d".repeat(600), "1.2.3.4");

        ArgumentCaptor<SystemLog> captor = ArgumentCaptor.forClass(SystemLog.class);
        verify(mapper).insert(captor.capture());
        SystemLog row = captor.getValue();
        assertEquals(9L, row.getUserId());
        assertEquals("auth", row.getModule());
        assertEquals("register", row.getAction());
        assertEquals("user", row.getTargetType());
        assertEquals(9L, row.getTargetId());
        assertEquals(500, row.getDetail().length(), "detail 截断到 500");
        assertEquals("1.2.3.4", row.getIp());
    }

    @Test
    void recordWithNullsSafe() {
        service.record(null, "permission", "matrix_update", null, null, null, null);
        ArgumentCaptor<SystemLog> captor = ArgumentCaptor.forClass(SystemLog.class);
        verify(mapper).insert(captor.capture());
        assertNull(captor.getValue().getTargetType());
        assertNull(captor.getValue().getUserId());
    }

    @Test
    void insertFailureDoesNotPropagate() {
        doThrow(new RuntimeException("db down")).when(mapper).insert(any(SystemLog.class));
        assertDoesNotThrow(() -> service.record(1L, "m", "a", null, null, null, null));
    }
}
