package com.linblog.common;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 分页查询参数（§4.2）：page≥1 默认 1；pageSize 1–50 默认 10；
 * keyword 服务端 trim 且截断到 ≤100（在 getter 中处理，控制器层无需重复写）。
 */
public class PageQuery {

    @Min(value = 1, message = "page 必须 ≥ 1")
    private int page = 1;

    @Min(value = 1, message = "pageSize 必须 ≥ 1")
    @Max(value = 50, message = "pageSize 必须 ≤ 50")
    private int pageSize = 10;

    private String keyword = "";

    public int getPage() {
        return page;
    }

    public void setPage(Integer page) {
        if (page != null) {
            this.page = page;
        }
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        if (pageSize != null) {
            this.pageSize = pageSize;
        }
    }

    public String getKeyword() {
        return keyword;
    }

    /** 服务端 trim + 长度截断 ≤100。 */
    public void setKeyword(String keyword) {
        if (keyword == null) {
            this.keyword = "";
            return;
        }
        String trimmed = keyword.strip();
        this.keyword = trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }

    public long offset() {
        return (long) (page - 1) * pageSize;
    }
}
