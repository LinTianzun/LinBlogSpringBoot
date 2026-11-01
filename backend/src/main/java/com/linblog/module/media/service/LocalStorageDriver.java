package com.linblog.module.media.service;

import com.linblog.config.AppProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 本地盘存储驱动（§4.3 STORAGE_LOCAL_DIR）：storage_key 形如 2026/10/ab12….webp，
 * 磁盘路径 = {localDir}/{storageKey}。
 */
@Component
public class LocalStorageDriver implements StorageDriver {

    private final AppProperties props;

    public LocalStorageDriver(AppProperties props) {
        this.props = props;
    }

    private Path base() {
        return Path.of(props.storageLocalDir()).toAbsolutePath().normalize();
    }

    @Override
    public String save(String key, byte[] data) throws IOException {
        Path target = base().resolve(key).normalize();
        if (!target.startsWith(base())) {
            throw new IOException("非法存储键: " + key);
        }
        Files.createDirectories(target.getParent());
        Files.write(target, data);
        return key;
    }

    @Override
    public byte[] load(String key) throws IOException {
        Path target = resolve(key);
        if (!Files.exists(target)) {
            return null;
        }
        return Files.readAllBytes(target);
    }

    @Override
    public boolean exists(String key) throws IOException {
        return Files.exists(resolve(key));
    }

    @Override
    public void remove(String key) throws IOException {
        Path target = resolve(key);
        if (Files.exists(target)) {
            Files.delete(target);
        }
    }

    @Override
    public Path resolve(String key) {
        Path target = base().resolve(key).normalize();
        if (!target.startsWith(base())) {
            throw new IllegalArgumentException("非法存储键: " + key);
        }
        return target;
    }
}
