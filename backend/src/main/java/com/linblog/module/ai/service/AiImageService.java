package com.linblog.module.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.ai.entity.AiModel;
import com.linblog.module.ai.entity.AiPendingImage;
import com.linblog.module.ai.entity.AiSetting;
import com.linblog.module.ai.mapper.AiModelMapper;
import com.linblog.module.ai.mapper.AiPendingImageMapper;
import com.linblog.module.ai.mapper.AiSettingMapper;
import com.linblog.module.media.dto.UploadResult;
import com.linblog.module.media.service.MediaService;
import com.linblog.security.AuthPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;

/**
 * AI 生图编排（§AI 图片域）：生成 → 水印裁除 → 暂存（一次性 token）→ 作者确认后落 media。
 * 铁律不变：候选图在作者点「采用」前不会写入任何业务数据；令牌与生成者绑定，他人的令牌不可消费。
 * 暂存放库里而非进程内存：多实例部署时任意实例都能取到同一份候选。
 */
@Service
public class AiImageService {

    private static final Logger log = LoggerFactory.getLogger(AiImageService.class);

    /** 候选图有效期（分钟，同原版） */
    public static final int PENDING_IMAGE_TTL_MINUTES = 10;
    /** 每用户最多同时挂着的候选数（超出时最旧的被清掉） */
    public static final int MAX_PENDING_IMAGES = 20;
    /** 生图最小超时（生图比对话慢，取配置与该值的较大者） */
    private static final int IMAGE_MIN_TIMEOUT_MS = 30_000;

    private final AiModelMapper modelMapper;
    private final AiSettingMapper settingMapper;
    private final AiPendingImageMapper pendingMapper;
    private final AiClientService client;
    private final AiWatermarkService watermarkService;
    private final AiService aiService;
    private final MediaService mediaService;
    private final SecureRandom random = new SecureRandom();

    public AiImageService(AiModelMapper modelMapper, AiSettingMapper settingMapper,
                          AiPendingImageMapper pendingMapper, AiClientService client,
                          AiWatermarkService watermarkService, AiService aiService,
                          MediaService mediaService) {
        this.modelMapper = modelMapper;
        this.settingMapper = settingMapper;
        this.pendingMapper = pendingMapper;
        this.client = client;
        this.watermarkService = watermarkService;
        this.aiService = aiService;
        this.mediaService = mediaService;
    }

    /** 解析配图模型：策略指定 → 默认模型（需配了图片模型名）→ 任一启用且配了图片模型的。 */
    private AiModel resolveImageModel() {
        AiSetting setting = aiService.settings();
        if (setting.getImageModelId() != null) {
            AiModel picked = modelMapper.selectById(setting.getImageModelId());
            if (picked != null && Boolean.TRUE.equals(picked.getEnabled())
                    && picked.getImageModel() != null && !picked.getImageModel().isBlank()) {
                return picked;
            }
        }
        AiModel fallback = modelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getEnabled, true)
                        .ne(AiModel::getImageModel, "")
                        .orderByDesc(AiModel::getIsDefault)
                        .orderByAsc(AiModel::getId)
                        .last("LIMIT 1")).stream().findFirst().orElse(null);
        if (fallback == null) {
            throw new BizException(ErrorCode.INTERNAL, "AI 生图未配置，请先在 AI 管理中为模型填写图片模型名");
        }
        return fallback;
    }

    /** 生图候选：{token, dataUri, prompt, modelName}。dataUri 仅供前端预览，采用走 token 确认。 */
    public Map<String, Object> generateCover(AuthPrincipal principal, String title, String introduce, String content) {
        if (title == null || title.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先填写标题，再生成 AI 封面");
        }
        AiService.ResolvedPrompt prompt = aiService.resolvePrompt("cover");
        String plainTitle = title.trim();
        String plainIntroduce = introduce == null ? "" : introduce.trim();
        String plainContent = content == null ? "" : content.trim();
        if (plainContent.length() > 600) plainContent = plainContent.substring(0, 600);
        String rendered = AiService.render(prompt.content(), Map.of(
                "title", plainTitle.length() > 100 ? plainTitle.substring(0, 100) : plainTitle,
                "introduce", plainIntroduce.length() > 200 ? plainIntroduce.substring(0, 200) : plainIntroduce,
                "content", plainContent,
                "introduceLine", plainIntroduce.isEmpty() ? "" : "文章简介：" + plainIntroduce,
                "contentLine", plainContent.isEmpty() ? "" : "正文摘要：" + plainContent));

        AiSetting setting = aiService.settings();
        AiModel model = resolveImageModel();
        long startedAt = System.currentTimeMillis();
        try {
            // 生图比对话慢：超时取模型配置与生图下限的较大者
            int originalTimeout = model.getTimeoutMs() == null ? 20000 : model.getTimeoutMs();
            model.setTimeoutMs(Math.max(originalTimeout, IMAGE_MIN_TIMEOUT_MS));
            AiClientService.ImageResult image = client.generateImage(model, rendered, model.getImageSize());
            // 兜底层：成图右下角仍有徽标时按策略裁除
            AiWatermarkService.Watermarked cleaned = watermarkService.removeWatermark(
                    image.data(), image.mimeType(),
                    !Boolean.FALSE.equals(setting.getRemoveWatermark()),
                    setting.getWatermarkMode());
            if (cleaned.removed()) {
                log.info("AI 成图右下角徽标已裁除 mode={}", setting.getWatermarkMode());
            }
            pruneExpired(principal.uid());
            String token = newToken();
            savePending(token, principal.uid(), "cover", cleaned.data(), cleaned.mimeType());
            aiService.logImageCall(principal, "cover", model, "success", null,
                    System.currentTimeMillis() - startedAt);
            String dataUri = "data:" + cleaned.mimeType() + ";base64,"
                    + java.util.Base64.getEncoder().encodeToString(cleaned.data());
            return Map.of(
                    "token", token,
                    "dataUri", dataUri,
                    "prompt", rendered,
                    "modelName", model.getName());
        } catch (BizException e) {
            aiService.logImageCall(principal, "cover", model,
                    e.getErrorCode() == ErrorCode.RATE_LIMITED ? "denied" : "failed",
                    e.getMessage(), System.currentTimeMillis() - startedAt);
            throw e;
        }
    }

    /** 一次性确认：token 归属与时效都合法时消费暂存并落 media，返回封面引用（mediaId/storageKey/url）。 */
    public Map<String, Object> confirmCover(AuthPrincipal principal, String token) {
        if (token == null || token.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "封面候选不存在或已过期，请重新生成");
        }
        AiPendingImage pending = pendingMapper.selectOne(new LambdaQueryWrapper<AiPendingImage>()
                .eq(AiPendingImage::getToken, token)
                .eq(AiPendingImage::getUserId, principal.uid())
                .gt(AiPendingImage::getExpiresAt, LocalDateTime.now())
                .last("LIMIT 1"));
        if (pending == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "封面候选不存在或已过期，请重新生成");
        }
        // 一次性消费：按全部条件删除；并发/重复消费删除影响行数为 0 → 视为已过期
        int deleted = pendingMapper.delete(new LambdaQueryWrapper<AiPendingImage>()
                .eq(AiPendingImage::getId, pending.getId()));
        if (deleted == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "封面候选不存在或已过期，请重新生成");
        }
        UploadResult saved = mediaService.persistImageBuffer(
                pending.getData(), pending.getMimeType(), "ai-cover-" + token + extensionOf(pending.getMimeType()));
        log.info("AI 封面候选已确认落库 mediaId={} size={}B", saved.id(), saved.sizeBytes());
        return Map.of(
                "mediaId", saved.id(),
                "storageKey", saved.storageKey(),
                "url", "/api/v1/media/" + saved.storageKey());
    }

    private void savePending(String token, long userId, String feature, byte[] data, String mimeType) {
        // 候选上限：超出时先清掉该用户最旧的几条，避免长期不用的人囤积行
        long held = pendingMapper.selectCount(new LambdaQueryWrapper<AiPendingImage>()
                .eq(AiPendingImage::getUserId, userId)
                .gt(AiPendingImage::getExpiresAt, LocalDateTime.now()));
        if (held >= MAX_PENDING_IMAGES) {
            pendingMapper.delete(new LambdaQueryWrapper<AiPendingImage>()
                    .eq(AiPendingImage::getUserId, userId)
                    .orderByAsc(AiPendingImage::getId)
                    .last("LIMIT " + Math.max(1, held - MAX_PENDING_IMAGES + 1)));
        }
        AiPendingImage row = new AiPendingImage();
        row.setToken(token);
        row.setUserId(userId);
        row.setFeature(feature);
        row.setMimeType(mimeType);
        row.setSize(data.length);
        row.setData(data);
        row.setExpiresAt(LocalDateTime.now().plusMinutes(PENDING_IMAGE_TTL_MINUTES));
        pendingMapper.insert(row);
    }

    private void pruneExpired(long userId) {
        pendingMapper.delete(new LambdaQueryWrapper<AiPendingImage>()
                .eq(AiPendingImage::getUserId, userId)
                .lt(AiPendingImage::getExpiresAt, LocalDateTime.now()));
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String extensionOf(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> ".img";
        };
    }
}
