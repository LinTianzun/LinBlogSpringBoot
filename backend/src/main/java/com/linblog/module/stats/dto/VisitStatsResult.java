package com.linblog.module.stats.dto;

import java.time.LocalDate;
import java.util.List;

/** 浏览折线（§5.8 GET /admin/overview/visit-stats?range=week|moon），缺数日补零。 */
public record VisitStatsResult(String range, List<DailyVisit> points) {

    public record DailyVisit(LocalDate date, long count) {
    }
}
