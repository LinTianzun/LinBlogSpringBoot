package com.linblog.module.stats.dto;

import java.util.List;

/** 概览汇总（§5.8 GET /admin/overview/summary）。 */
public record SummaryResult(long contents, long comments, long unreadMessages, long files,
                            long viewsToday, long viewsWeek) {
}
