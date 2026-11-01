package com.linblog.module.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.config.AppProperties;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.media.dto.UploadResult;
import com.linblog.module.media.entity.FileAttachment;
import com.linblog.module.media.entity.Media;
import com.linblog.module.media.mapper.FileAttachmentMapper;
import com.linblog.module.media.mapper.MediaMapper;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * 媒体管线（§5.7/§7.5）：上传→Tika 探型→图片 sha256 去重→落盘→事务提交后
 * 异步虚拟线程生成 THUMB_WIDTHS WebP 缩略图→回写 thumb_key。非图片走 files 表。
 */
@Service
public class MediaService {

    private static final DateTimeFormatter YEAR_FMT = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("MM");

    private final MediaMapper mediaMapper;
    private final FileAttachmentMapper fileAttachmentMapper;
    private final StorageDriver storageDriver;
    private final AppProperties props;
    private final Tika tika = new Tika();
    private final SystemLogService systemLogService;
    private final ExecutorService thumbnailExecutor;

    public MediaService(MediaMapper mediaMapper, FileAttachmentMapper fileAttachmentMapper,
                        StorageDriver storageDriver, AppProperties props,
                        SystemLogService systemLogService, ExecutorService thumbnailExecutor) {
        this.mediaMapper = mediaMapper;
        this.fileAttachmentMapper = fileAttachmentMapper;
        this.storageDriver = storageDriver;
        this.props = props;
        this.systemLogService = systemLogService;
        this.thumbnailExecutor = thumbnailExecutor;
    }

    public UploadResult upload(MultipartFile file) {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未登录");
        }
        if (!principal.has(com.linblog.module.permission.PermissionKeys.FILE_UPLOAD)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权限执行该操作");
        }
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件不能为空");
        }
        if (file.getSize() > props.uploadMaxBytes().toBytes()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件超出大小限制");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "读取上传内容失败");
        }
        String detected = detectMime(data, file.getOriginalFilename());
        String hash = sha256Hex(data);
        if (detected.startsWith("image/")) {
            return saveImage(principal, data, detected, hash, file.getOriginalFilename());
        }
        return saveFile(principal, data, detected, hash, file.getOriginalFilename());
    }

    private String detectMime(byte[] data, String originalName) {
        try {
            String detected = tika.detect(new ByteArrayInputStream(data), originalName);
            if (detected == null || detected.isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "无法识别文件类型");
            }
            return detected;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "无法识别文件类型");
        }
    }

    private UploadResult saveImage(AuthPrincipal principal, byte[] data, String mime,
                                   String hash, String originalName) {
        Media exist = mediaMapper.selectOne(new LambdaQueryWrapper<Media>()
                .eq(Media::getSha256, hash)
                .eq(Media::getState, "active")
                .last("LIMIT 1"));
        if (exist != null) {
            // §7.5 去重：同 hash 复用已有对象
            return new UploadResult(exist.getId(), exist.getStorageKey(),
                    "/api/v1/media/" + exist.getStorageKey(), exist.getKind(), exist.getMimeType(),
                    exist.getSizeBytes(), true);
        }
        Media media = new Media();
        String ext = extensionOf(mime);
        String key = newKey(ext);
        media.setStorageKey(key);
        media.setKind("image");
        media.setSha256(hash);
        media.setOriginalName(originalName);
        media.setMimeType(mime);
        media.setSizeBytes((long) data.length);
        media.setUploaderId(principal.uid());
        media.setState("active");
        try {
            storageDriver.save(key, data);
            readDimensions(media, data);
            mediaMapper.insert(media);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "保存文件失败");
        }
        final Long mediaId = media.getId();
        final String storageKey = key;
        registerAfterCommit(() -> generateThumbnails(mediaId, storageKey, data));
        systemLogService.record(principal.uid(), "media", "upload", "media", media.getId(),
                originalName, null);
        return new UploadResult(media.getId(), key, "/api/v1/media/" + key, media.getKind(),
                mime, (long) data.length, false);
    }

    private UploadResult saveFile(AuthPrincipal principal, byte[] data, String mime,
                                  String hash, String originalName) {
        FileAttachment attachment = new FileAttachment();
        String ext = extensionOf(mime);
        String key = newKey(ext);
        attachment.setStorageKey(key);
        attachment.setUploaderId(principal.uid());
        attachment.setOriginalName(originalName);
        attachment.setMimeType(mime);
        attachment.setSizeBytes((long) data.length);
        attachment.setSha256(hash);
        attachment.setState("active");
        try {
            storageDriver.save(key, data);
            fileAttachmentMapper.insert(attachment);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "保存文件失败");
        }
        systemLogService.record(principal.uid(), "media", "upload", "file", attachment.getId(),
                originalName, null);
        return new UploadResult(attachment.getId(), key, "/api/v1/media/" + key, "file",
                mime, (long) data.length, false);
    }

    private void readDimensions(Media media, byte[] data) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image != null) {
                media.setWidth(image.getWidth());
                media.setHeight(image.getHeight());
            }
        } catch (IOException | RuntimeException ignored) {
            // 读不出尺寸不阻塞上传
        }
    }

    /** 事务提交后异步生成缩略图（§7.5）；无活动事务时直接执行。 */
    private void registerAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    thumbnailExecutor.submit(task);
                }
            });
        } else {
            thumbnailExecutor.submit(task);
        }
    }

    void generateThumbnails(Long mediaId, String storageKey, byte[] original) {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(original));
        } catch (IOException e) {
            return;
        }
        if (source == null) {
            return;
        }
        List<Integer> widths = props.thumbWidthList();
        String firstThumbKey = null;
        for (Integer width : widths) {
            try {
                if (source.getWidth() <= width) {
                    continue;
                }
                byte[] webp = encodeWebp(source, width);
                String thumbKey = "t" + width + "/" + storageKey;
                storageDriver.save(thumbKey, webp);
                if (firstThumbKey == null) {
                    firstThumbKey = thumbKey;
                }
            } catch (IOException | RuntimeException e) {
                systemLogService.record(null, "media", "thumb_error", "media", mediaId,
                        "width=" + width + ": " + e.getMessage(), null);
            }
        }
        if (firstThumbKey != null) {
            Media media = mediaMapper.selectById(mediaId);
            if (media != null && media.getThumbKey() == null) {
                media.setThumbKey(firstThumbKey);
                mediaMapper.updateById(media);
            }
        }
    }

    /** webp-imageio 编码（质量 0.8）；失败退回 PNG 键名（浏览器自行兼容）。 */
    private byte[] encodeWebp(BufferedImage source, int width) throws IOException {
        int height = Math.max(1, Math.round(source.getHeight() * (float) width / source.getWidth()));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        try {
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType("image/webp");
        ImageWriter writer = writers.hasNext() ? writers.next() : null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (writer == null) {
            ImageIO.write(scaled, "png", out);
            return out.toByteArray();
        }
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            ImageOutputStream stream = ImageIO.createImageOutputStream(out);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(scaled, null, null), param);
            stream.close();
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** {yyyy}/{MM}/{32hex}.{ext} 存储键。 */
    private String newKey(String ext) {
        String random = HexFormat.of().formatHex(secureRandom32());
        LocalDateTime now = LocalDateTime.now();
        return now.format(YEAR_FMT) + "/" + now.format(MONTH_FMT) + "/" + random + "." + ext;
    }

    private byte[] secureRandom32() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private String extensionOf(String mime) {
        return switch (mime) {
            case "image/webp" -> "webp";
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/svg+xml" -> "svg";
            default -> {
                int slash = mime.indexOf('/');
                yield slash >= 0 && mime.length() > slash + 1
                        ? mime.substring(slash + 1).replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT)
                        : "bin";
            }
        };
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
