package com.linblog.module.content.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * subset_items 关联实体（§3.2）：联合主键 (subset_id, content_id)，sort 为展示顺序。
 */
@TableName("subset_items")
public class SubsetItem {

    private Long subsetId;
    private Long contentId;
    private Integer sort;

    public Long getSubsetId() {
        return subsetId;
    }

    public void setSubsetId(Long subsetId) {
        this.subsetId = subsetId;
    }

    public Long getContentId() {
        return contentId;
    }

    public void setContentId(Long contentId) {
        this.contentId = contentId;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }
}
