package com.linblog.module.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linblog.module.media.entity.Media;
import com.linblog.module.media.mapper.MediaMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 系统默认头像（§5.3 用户创建即有头像）：按用户名确定性生成 SVG（首字母 + 由用户名
 * hash 决定的底色，同一用户名永远得到同一张），按内容 sha256 去重复用 media 记录。
 * SVG 方案理由：无需 Java2D 位图管线、体积 ~300B、任意分辨率清晰，且与本站
 * avatar 上传（image/*）走同一条 /api/v1/media/{storageKey} 展示链路。
 */
@Service
public class DefaultAvatarService {

    /** 与站点主题一致的确定性底色池（亮卡片主题色 + 三套 accent） */
    private static final String[] PALETTE = {
            "#e0553d", "#d98324", "#18a058", "#3b6ce0", "#7c5cff", "#0fa8a8",
    };

    private final MediaMapper mediaMapper;
    private final StorageDriver storageDriver;

    public DefaultAvatarService(MediaMapper mediaMapper, StorageDriver storageDriver) {
        this.mediaMapper = mediaMapper;
        this.storageDriver = storageDriver;
    }

    /**
     * 返回该用户名对应的默认头像 media（已存在同内容则复用，不重复落盘）。
     *
     * @param username   用户名（决定首字母与底色）
     * @param uploaderId 上传者（media.uploader_id NOT NULL，传创建操作者）
     */
    public Media ensureDefaultAvatar(String username, long uploaderId) {
        String name = username == null || username.isBlank() ? "?" : username.trim();
        String initial = escapeXml(name.substring(0, 1).toUpperCase());
        String color = PALETTE[Math.floorMod(name.hashCode(), PALETTE.length)];
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="128" height="128" viewBox="0 0 128 128">
                  <rect width="128" height="128" rx="30" fill="%s"/>
                  <text x="64" y="64" dy=".35em" text-anchor="middle"
                        font-family="Helvetica, Arial, sans-serif" font-size="62" font-weight="600" fill="#ffffff">%s</text>
                </svg>
                """.formatted(color, initial);
        byte[] data = svg.getBytes(StandardCharsets.UTF_8);
        String hash = sha256Hex(data);

        Media exist = mediaMapper.selectOne(new LambdaQueryWrapper<Media>()
                .eq(Media::getSha256, hash)
                .eq(Media::getState, "active")
                .last("LIMIT 1"));
        if (exist != null) {
            return exist;
        }
        Media media = new Media();
        String key = "avatar/default-" + hash.substring(0, 16) + ".svg";
        media.setStorageKey(key);
        media.setKind("image");
        media.setSha256(hash);
        media.setOriginalName("default-avatar.svg");
        media.setMimeType("image/svg+xml");
        media.setWidth(128);
        media.setHeight(128);
        media.setSizeBytes((long) data.length);
        media.setUploaderId(uploaderId);
        media.setState("active");
        try {
            storageDriver.save(key, data);
            mediaMapper.insert(media);
        } catch (java.io.IOException e) {
            throw new com.linblog.common.BizException(
                    com.linblog.common.ErrorCode.INTERNAL, "默认头像生成失败");
        }
        return media;
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
