package com.linblog.module.ai.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AI 生图确认流：一次性消费、生成者绑定、过期即失效；确认后经 MediaService 落库。 */
class AiImageServiceTest {

    private AiModelMapper modelMapper;
    private AiSettingMapper settingMapper;
    private AiPendingImageMapper pendingMapper;
    private AiClientService client;
    private AiWatermarkService watermarkService;
    private AiService aiService;
    private MediaService mediaService;
    private AiImageService service;

    private final AuthPrincipal user = new AuthPrincipal(9L, "alice", "editor", false, Set.of());

    @BeforeEach
    void setUp() {
        modelMapper = mock(AiModelMapper.class);
        settingMapper = mock(AiSettingMapper.class);
        pendingMapper = mock(AiPendingImageMapper.class);
        client = mock(AiClientService.class);
        watermarkService = mock(AiWatermarkService.class);
        aiService = mock(AiService.class);
        mediaService = mock(MediaService.class);
        service = new AiImageService(modelMapper, settingMapper, pendingMapper, client,
                watermarkService, aiService, mediaService);

        AiSetting setting = new AiSetting();
        setting.setId(1);
        setting.setQuotaEnabled(false);
        setting.setRemoveWatermark(true);
        setting.setWatermarkMode("crop");
        when(aiService.settings()).thenReturn(setting);
        // 走 mock 的模型解析（AiService.resolvePrompt/resolveModel 依赖 mapper，直接打桩编排层的协作者）
        when(aiService.resolvePrompt(org.mockito.ArgumentMatchers.anyString())).thenReturn(
                new AiService.ResolvedPrompt("生成一张图：{{title}}", "封面配图", true));
        AiModel model = new AiModel();
        model.setId(3);
        model.setName("主力模型");
        model.setEnabled(true);
        model.setImageModel("cogview-test");
        when(modelMapper.selectList(any())).thenReturn(List.of(model));
        when(client.generateImage(any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AiClientService.ImageResult(new byte[]{1, 2, 3}, "image/png"));
        when(watermarkService.removeWatermark(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new AiWatermarkService.Watermarked(new byte[]{1, 2, 3}, "image/png", false));
    }

    @Test
    void generateCoverSavesPendingAndReturnsToken() {
        when(pendingMapper.selectCount(any())).thenReturn(0L);
        Map<String, Object> out = service.generateCover(user, "标题", "", "");

        String token = (String) out.get("token");
        assertTrue(token != null && !token.isEmpty());
        assertTrue(((String) out.get("dataUri")).startsWith("data:image/png;base64,"));
        ArgumentCaptor<AiPendingImage> captor = org.mockito.ArgumentCaptor.forClass(AiPendingImage.class);
        verify(pendingMapper).insert(captor.capture());
        AiPendingImage saved = captor.getValue();
        assertEquals(9L, saved.getUserId());
        // TTL 10 分钟（mock insert 不回填 createdAt，直接对 expiresAt 断言，±2 分钟容差）
        long minutesFromNow = java.time.Duration.between(
                java.time.LocalDateTime.now(), saved.getExpiresAt()).toMinutes();
        assertTrue(Math.abs(minutesFromNow - 10) <= 2, "TTL 应为 10 分钟，实际 " + minutesFromNow + " 分钟");
    }

    @Test
    void generateCoverRequiresTitle() {
        BizException ex = assertThrows(BizException.class, () -> service.generateCover(user, " ", "", ""));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void confirmConsumesPendingOnce() {
        AiPendingImage pending = new AiPendingImage();
        pending.setId(1L);
        pending.setToken("tok");
        pending.setUserId(9L);
        pending.setMimeType("image/png");
        pending.setData(new byte[]{1});
        pending.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(pendingMapper.selectOne(any())).thenReturn(pending);
        when(pendingMapper.delete(any())).thenReturn(1);
        when(mediaService.persistImageBuffer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new UploadResult(77L, "2026/x.png", "/api/v1/media/2026/x.png", "image", "image/png", 3L, false));

        Map<String, Object> out = service.confirmCover(user, "tok");

        assertEquals(77L, out.get("mediaId"));
        assertEquals("/api/v1/media/2026/x.png", out.get("url"));
    }

    @Test
    void confirmExpiredOrForeignTokenRejected() {
        // 查不到（过期 / 他人令牌 / 已消费）→ selectOne 返回 null
        when(pendingMapper.selectOne(any())).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.confirmCover(user, "gone"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }
}
