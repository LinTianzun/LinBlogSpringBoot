package com.linblog.module.media.service;

import com.linblog.config.AppProperties;
import com.linblog.support.TestAppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 本地存储驱动：路径穿越防护与读写删。 */
class LocalStorageDriverTest {

    @TempDir
    Path tempDir;

    private LocalStorageDriver driver;

    @BeforeEach
    void setUp() {
        AppProperties props = TestAppProperties.of(new Object[]{8, tempDir.toString()});
        driver = new LocalStorageDriver(props);
    }

    @Test
    void saveLoadExistsRemoveRoundTrip() throws IOException {
        byte[] data = "内容".getBytes(StandardCharsets.UTF_8);
        String key = "2026/05/abc.png";
        assertEquals(key, driver.save(key, data));
        assertTrue(driver.exists(key));
        assertArrayEquals(data, driver.load(key));

        driver.remove(key);
        assertFalse(driver.exists(key));
    }

    @Test
    void loadMissingReturnsNull() throws IOException {
        assertEquals(null, driver.load("2026/05/nope.png"));
    }

    @Test
    void traversalRejected() {
        assertThrows(IllegalArgumentException.class, () -> driver.resolve("../escape.txt"));
        assertThrows(IOException.class, () -> driver.save("../escape.txt", new byte[]{1}));
        assertThrows(IOException.class, () -> driver.save("a/../../escape.txt", new byte[]{1}));
    }

    @Test
    void filesLandInsideBase() throws IOException {
        String key = "2026/06/ok.txt";
        driver.save(key, "x".getBytes(StandardCharsets.UTF_8));
        Path resolved = driver.resolve(key);
        assertTrue(resolved.startsWith(tempDir.toAbsolutePath().normalize()));
        assertTrue(Files.exists(resolved));
    }
}
