package cn.jia.chat.tool;

import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.util.StringUtil;
import cn.jia.task.common.TaskConstants;
import cn.jia.task.entity.TaskPlanEntity;
import cn.jia.task.service.TaskService;
import com.github.pagehelper.PageInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class TaskTools {

    private final TaskService taskService;

    @Tool(name = "createTaskPlan", description = "创建任务计划，用于设置提醒、目标跟踪、还款计划等")
    public Map<String, Object> createTaskPlan(
            @ToolParam(description = "任务名称") String name,
            @ToolParam(description = "任务描述") String description,
            @ToolParam(description = "周期: 0长期 1每年 2每月 3每周 5每日 6指定日期") Integer period,
            @ToolParam(description = "金额/数量") BigDecimal amount,
            @ToolParam(description = "是否提醒: 1是 0否") Integer remind) {

        EsContext ctx = EsContextHolder.getContext();
        TaskPlanEntity entity = new TaskPlanEntity();
        entity.setJiacn(ctx.getJiacn());
        entity.setClientId(ctx.getClientId());
        entity.setName(name);
        entity.setDescription(description);
        entity.setPeriod(period != null ? period : TaskConstants.TASK_PERIOD_ALLTIME);
        entity.setType(TaskConstants.TASK_TYPE_NOTIFY);
        entity.setStatus(TaskConstants.TASK_STATUS_ENABLE);
        entity.setRemind(remind != null ? remind : TaskConstants.TASK_REMIND_NO);
        entity.setAmount(amount);
        taskService.create(entity);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entity.getId());
        result.put("name", entity.getName());
        result.put("status", "created");
        return result;
    }

    @Tool(name = "getTaskPlan", description = "根据ID查询任务计划详情")
    public Map<String, Object> getTaskPlan(
            @ToolParam(description = "任务计划ID") Long id) {
        TaskPlanEntity entity = taskService.get(id);
        if (entity == null) {
            return Map.of("notFound", true, "id", id);
        }
        return toMap(entity);
    }

    @Tool(name = "searchTaskPlans", description = "搜索任务计划列表，支持任务名称模糊匹配")
    public Map<String, Object> searchTaskPlans(
            @ToolParam(description = "搜索关键词(匹配任务名称)，为空则返回全部") String keyword,
            @ToolParam(description = "页码，默认1") Integer pageNum,
            @ToolParam(description = "每页条数，默认20") Integer pageSize) {

        int page = pageNum != null ? pageNum : 1;
        int size = pageSize != null ? pageSize : 20;

        TaskPlanEntity query = new TaskPlanEntity();
        EsContext ctx = EsContextHolder.getContext();
        query.setClientId(ctx.getClientId());
        if (!StringUtil.isBlank(keyword)) {
            query.setName(keyword);
        }

        PageInfo<TaskPlanEntity> pageInfo = taskService.findPage(query, page, size);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageInfo.getTotal());
        result.put("pageNum", pageInfo.getPageNum());
        result.put("pageSize", pageInfo.getPageSize());
        result.put("list", pageInfo.getList().stream().map(this::toMap).toList());
        return result;
    }

    @Tool(name = "cancelTaskPlan", description = "取消一个任务计划，任务将失效")
    public Map<String, Object> cancelTaskPlan(
            @ToolParam(description = "任务计划ID") Long id) {
        taskService.cancel(id);
        return Map.of("id", id, "status", "cancelled");
    }

    @Tool(name = "deleteTaskPlan", description = "删除一个任务计划")
    public Map<String, Object> deleteTaskPlan(
            @ToolParam(description = "任务计划ID") Long id) {
        boolean deleted = taskService.delete(id);
        return Map.of("id", id, "deleted", deleted);
    }

    private Map<String, Object> toMap(TaskPlanEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("name", entity.getName());
        map.put("description", entity.getDescription());
        map.put("type", entity.getType());
        map.put("period", entity.getPeriod());
        map.put("amount", entity.getAmount());
        map.put("remind", entity.getRemind());
        map.put("status", entity.getStatus());
        map.put("jiacn", entity.getJiacn());
        map.put("clientId", entity.getClientId());
        return map;
    }
}