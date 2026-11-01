package com.linblog.module.stats.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linblog.module.stats.entity.ViewLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface ViewLogMapper extends BaseMapper<ViewLog> {

    /** 按自然日聚合计数（§5.8 visit-stats 折线；零值天由服务端补齐）。 */
    @Select("SELECT DATE(viewed_at) AS day, COUNT(*) AS total FROM view_logs "
            + "WHERE viewed_at >= #{from} GROUP BY DATE(viewed_at)")
    List<Map<String, Object>> selectDailyCounts(@Param("from") LocalDateTime from);
}
