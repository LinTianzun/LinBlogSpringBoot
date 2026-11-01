package com.linblog.module.content.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * content_labels 关联实体（§3.2）：联合主键 (content_id, label_id)，无自增 id。
 */
@TableName("content_labels")
public class ContentLabel {

    private Long contentId;
    private Long labelId;

    public Long getContentId() {
        return contentId;
    }

    public void setContentId(Long contentId) {
        this.contentId = contentId;
    }

    public Long getLabelId() {
        return labelId;
    }

    public void setLabelId(Long labelId) {
        this.labelId = labelId;
    }
}
