package com.linblog.module.ai.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** ai_prompt_binding 表实体：功能 → 选用的提示词模板（feature_key 为主键）。 */
@TableName("ai_prompt_binding")
public class AiPromptBinding {

    @TableId
    private String featureKey;
    private String templateKey;
    private LocalDateTime updatedAt;

    public String getFeatureKey() { return featureKey; }
    public void setFeatureKey(String featureKey) { this.featureKey = featureKey; }
    public String getTemplateKey() { return templateKey; }
    public void setTemplateKey(String templateKey) { this.templateKey = templateKey; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
