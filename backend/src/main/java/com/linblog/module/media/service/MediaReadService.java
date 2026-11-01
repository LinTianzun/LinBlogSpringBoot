package com.linblog.module.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.config.AppProperties;
import com.linblog.module.media.entity.Media;
import com.linblog.module.media.mapper.MediaMapper;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 媒体读取（§5.7）：GET /api/v1/media/{storageKey}?w=320|640|1280 公开。
 * 非图片或非 active → 40300；?w 必须在 THUMB_WIDTHS；缩略缺失回退原图。
 */
@Service
public class MediaReadService {

    private final MediaMapper mediaMapper;
    private final StorageDriver storageDriver;
    private final AppProperties props;

    public MediaReadService(MediaMapper mediaMapper, StorageDriver storageDriver, AppProperties props) {
        this.mediaMapper = mediaMapper;
        this.storageDriver = storageDriver;
        this.props = props;
    }

    public record MediaPayload(Resource resource, MediaType mediaType, String filename) {
    }

    public MediaPayload load(String storageKey, Integer width) {
        Media media = mediaMapper.selectOne(new LambdaQueryWrapper<Media>()
                .eq(Media::getStorageKey, storageKey)
                .last("LIMIT 1"));
        if (media == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "媒体不存在");
        }
        if (!"image".equals(media.getKind()) || !"active".equals(media.getState())) {
            throw new BizException(ErrorCode.FORBIDDEN, "媒体不可访问");
        }
        String key = media.getStorageKey();
        if (width != null) {
            if (!props.thumbWidthList().contains(width)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "不支持的缩略尺寸");
            }
            key = "t" + width + "/" + storageKey;
            if (!storageExists(key)) {
                // 缩略未生成/读不到 → 回退原图
                key = media.getStorageKey();
            }
        }
        byte[] data = storageLoad(key);
        if (data == null && !key.equals(media.getStorageKey())) {
            key = media.getStorageKey();
            data = storageLoad(key);
        }
        if (data == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "媒体文件缺失");
        }
        // 缩略图由生成管线统一写 WebP（键仍带原扩展名），命中缩略时按 webp 下发
        boolean thumbnail = width != null && !key.equals(media.getStorageKey());
        MediaType mediaType = thumbnail || key.endsWith(".webp")
                ? MediaType.parseMediaType("image/webp")
                : MediaType.parseMediaType(media.getMimeType());
        return new MediaPayload(new ByteArrayResource(data), mediaType, filenameOf(key));
    }

    private boolean storageExists(String key) {
        try {
            return storageDriver.exists(key);
        } catch (IOException e) {
            return false;
        }
    }

    private byte[] storageLoad(String key) {
        try {
            return storageDriver.load(key);
        } catch (IOException e) {
            return null;
        }
    }

    private String filenameOf(String key) {
        int slash = key.lastIndexOf('/');
        return slash >= 0 ? key.substring(slash + 1) : key;
    }

    /** 清理调度：返回 trashed 超期的媒体（供 scheduler 删除对象+行）。 */
    public List<Media> expiredTrashedMedia(LocalDateTime cutoff) {
        return mediaMapper.selectList(new LambdaQueryWrapper<Media>()
                .eq(Media::getState, "trash")
                .lt(Media::getTrashedAt, cutoff));
    }
}
