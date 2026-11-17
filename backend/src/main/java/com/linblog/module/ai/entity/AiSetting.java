package com.linblog.module.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** ai_setting 表实体：AI 策略（单行 id=1）。 */
@TableName("ai_setting")
public class AiSetting {

    @TableId(type = IdType.INPUT)
    private Integer id;
    private Boolean quotaEnabled;
    private Integer dailyLimit;
    private Integer monthlyLimit;
    private Boolean superAdminUnlimited;
    private Integer chatModelId;
    private Integer imageModelId;
    private Boolean removeWatermark;
    private String watermarkMode;
    private LocalDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Boolean getQuotaEnabled() { return quotaEnabled; }
    public void setQuotaEnabled(Boolean quotaEnabled) { this.quotaEnabled = quotaEnabled; }
    public Integer getDailyLimit() { return dailyLimit; }
    public void setDailyLimit(Integer dailyLimit) { this.dailyLimit = dailyLimit; }
    public Integer getMonthlyLimit() { return monthlyLimit; }
    public void setMonthlyLimit(Integer monthlyLimit) { this.monthlyLimit = monthlyLimit; }
    public Boolean getSuperAdminUnlimited() { return superAdminUnlimited; }
    public void setSuperAdminUnlimited(Boolean superAdminUnlimited) { this.superAdminUnlimited = superAdminUnlimited; }
    public Integer getChatModelId() { return chatModelId; }
    public void setChatModelId(Integer chatModelId) { this.chatModelId = chatModelId; }
    public Integer getImageModelId() { return imageModelId; }
    public void setImageModelId(Integer imageModelId) { this.imageModelId = imageModelId; }
    public Boolean getRemoveWatermark() { return removeWatermark; }
    public void setRemoveWatermark(Boolean removeWatermark) { this.removeWatermark = removeWatermark; }
    public String getWatermarkMode() { return watermarkMode; }
    public void setWatermarkMode(String watermarkMode) { this.watermarkMode = watermarkMode; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
