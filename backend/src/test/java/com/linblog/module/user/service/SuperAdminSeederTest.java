package com.linblog.module.user.service;

import com.linblog.module.user.entity.User;
import com.linblog.module.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 超管种子：仅当库中无 super_admin 时创建 admin / admin@linlog.com。 */
class SuperAdminSeederTest {

    private UserMapper userMapper;
    private SuperAdminSeeder seeder;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        seeder = new SuperAdminSeeder(userMapper, encoder);
    }

    @Test
    void seedsAdminWhenNoSuperAdmin() {
        when(userMapper.selectCount(any())).thenReturn(0L);

        seeder.run(new DefaultApplicationArguments());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User seeded = captor.getValue();
        assertEquals(SuperAdminSeeder.SEED_USERNAME, seeded.getUsername());
        assertEquals(SuperAdminSeeder.SEED_EMAIL, seeded.getEmail());
        assertEquals("super_admin", seeded.getRoleCode());
        assertEquals(1, seeded.getStatus());
        assertTrue(encoder.matches(SuperAdminSeeder.SEED_PASSWORD, seeded.getPasswordHash()),
                "密码哈希必须可验证（明文不落库）");
        assertNotEquals(SuperAdminSeeder.SEED_PASSWORD, seeded.getPasswordHash());
    }

    @Test
    void skipsWhenSuperAdminExists() {
        when(userMapper.selectCount(any())).thenReturn(1L);
        seeder.run(new DefaultApplicationArguments());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void concurrentDuplicateIsSwallowed() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uk_single_super_admin"))
                .when(userMapper).insert(any(User.class));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> seeder.run(new DefaultApplicationArguments()));
    }
}
