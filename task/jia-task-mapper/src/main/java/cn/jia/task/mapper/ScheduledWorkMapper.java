package cn.jia.task.mapper;

import cn.jia.task.entity.ScheduledWorkRow;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import cn.jia.task.scheduling.ScheduleKey;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/** Explicit process partition on every operation, including discovery before ORDER/LIMIT. */
// SQL owns this table's process partition; avoid a second parser rewriting locking clauses.
@InterceptorIgnore(tenantLine = "true")
public interface ScheduledWorkMapper {
    void ensure(@Param("tenant") String tenant, @Param("key") ScheduleKey key);
    ScheduledWorkRow lock(@Param("tenant") String tenant, @Param("key") ScheduleKey key);
    int replace(@Param("row") ScheduledWorkRow row, @Param("expectedVersion") long expectedVersion);
    List<ScheduledWorkRow> due(@Param("tenant") String tenant, @Param("client") String client,
            @Param("node") String node, @Param("now") long now, @Param("limit") int limit,
            @Param("handlers") java.util.Set<String> handlers, @Param("included") boolean included);
    Long nextWake(@Param("tenant") String tenant, @Param("client") String client, @Param("node") String node);
    Long nextDelay(@Param("tenant") String tenant, @Param("client") String client, @Param("node") String node,
            @Param("handlers") java.util.Set<String> handlers, @Param("included") boolean included);
    @Select("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)")
    long nowMillis();
}
