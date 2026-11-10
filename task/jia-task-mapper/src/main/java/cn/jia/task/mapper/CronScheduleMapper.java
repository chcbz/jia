package cn.jia.task.mapper;

import cn.jia.task.entity.CronScheduleRow;
import cn.jia.task.scheduling.CronSchedule;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** All discovery is an exact configured tenant/client/handler/node key, before locking. */
@InterceptorIgnore(tenantLine = "true")
public interface CronScheduleMapper {
    @Insert("INSERT INTO task_cron_schedule(tenant_id,client_id,handler,target_node,cron_expression,zone_id,misfire_policy,enabled,next_fire_at) "
            + "VALUES(#{tenant},#{d.clientId},#{d.handler},#{d.targetNode},#{d.expression},#{d.zoneId},#{d.misfire},1,#{next}) "
            + "ON DUPLICATE KEY UPDATE tenant_id=tenant_id")
    void ensure(@Param("tenant") String tenant, @Param("d") CronSchedule definition, @Param("next") Long next);

    @Select("SELECT tenant_id AS tenantId,client_id AS clientId,handler,target_node AS targetNode,"
            + "cron_expression AS expression,zone_id AS zoneId,misfire_policy AS misfire,enabled,next_fire_at AS nextFireAt "
            + "FROM task_cron_schedule WHERE tenant_id=#{tenant} AND client_id=#{client} AND handler=#{handler} AND target_node=#{node} FOR UPDATE")
    CronScheduleRow lock(@Param("tenant") String tenant,@Param("client") String client,
            @Param("handler") String handler,@Param("node") String node);

    @Update("UPDATE task_cron_schedule SET cron_expression=#{d.expression},zone_id=#{d.zoneId},misfire_policy=#{d.misfire},"
            + "enabled=#{enabled},next_fire_at=#{next} WHERE tenant_id=#{tenant} AND client_id=#{d.clientId} "
            + "AND handler=#{d.handler} AND target_node=#{d.targetNode}")
    int replace(@Param("tenant") String tenant,@Param("d") CronSchedule definition,
            @Param("enabled") boolean enabled,@Param("next") Long next);
}
