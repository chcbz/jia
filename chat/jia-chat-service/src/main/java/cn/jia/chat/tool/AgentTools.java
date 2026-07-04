package cn.jia.chat.tool;

import cn.jia.agent.entity.AgentCapabilityDTO;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskNoteDTO;
import cn.jia.agent.entity.AgentTaskReportDTO;
import cn.jia.agent.entity.AgentTaskSearchDTO;
import cn.jia.agent.entity.AgentTaskRecommendationDTO;
import cn.jia.agent.service.AgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AgentTools {

    private final AgentService agentService;

    @Tool(name = "listAgents", description = "列出所有在线AI智能体及其能力信息，用于了解可调用的智能体资源")
    public Map<String, Object> listAgents(
            @ToolParam(description = "状态过滤：online/busy/offline，为空则返回全部") String status,
            @ToolParam(description = "能力过滤关键词，如 coordination/planning") String ability,
            @ToolParam(description = "页码，默认1") Integer pageNum,
            @ToolParam(description = "每页条数，默认10") Integer pageSize) {
        var page = agentService.list(
                status != null ? status : "",
                ability != null ? ability : "",
                pageNum != null ? pageNum : 1,
                pageSize != null ? pageSize : 10);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.getTotal());
        result.put("pageNum", page.getPageNum());
        result.put("pageSize", page.getPageSize());
        result.put("list", page.getList());
        return result;
    }

    @Tool(name = "getAgentDetail", description = "获取指定智能体的详细信息，包括状态和能力")
    public AgentRuntimeDTO getAgentDetail(
            @ToolParam(description = "智能体ID") String agentId) {
        return agentService.get(agentId);
    }

    @Tool(name = "listAgentCapabilities", description = "列出所有智能体的能力清单，用于任务分配时匹配合适的智能体")
    public List<AgentCapabilityDTO> listAgentCapabilities() {
        return agentService.listCapabilities();
    }

    @Tool(name = "createAgentTask", description = "创建新任务，指定标题、描述和所需能力，用于向智能体派发工作")
    public AgentTaskDTO createAgentTask(
            @ToolParam(description = "任务标题") String title,
            @ToolParam(description = "任务详细描述") String description,
            @ToolParam(description = "所需能力列表，如 [\"planning\", \"research\"]") List<String> requiredAbilities,
            @ToolParam(description = "任务奖励积分") Integer reward) {
        AgentTaskCreateDTO request = new AgentTaskCreateDTO();
        request.setTitle(title);
        request.setDescription(description);
        request.setRequiredAbilities(requiredAbilities);
        request.setReward(reward);
        return agentService.createTask(request);
    }

    @Tool(name = "getAgentTask", description = "查询指定任务的详细信息")
    public AgentTaskDTO getAgentTask(
            @ToolParam(description = "任务ID") String taskId) {
        return agentService.getTask(taskId);
    }

    @Tool(name = "searchAgentTasks", description = "搜索智能体任务，支持按状态、能力等条件过滤")
    public Map<String, Object> searchAgentTasks(
            @ToolParam(description = "任务状态：open/assigned/running/completed/failed/archived") String status,
            @ToolParam(description = "页码，默认1") Integer pageNum,
            @ToolParam(description = "每页条数，默认10") Integer pageSize) {
        AgentTaskSearchDTO request = new AgentTaskSearchDTO();
        request.setStatus(status);
        var page = agentService.searchTasks(request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.getTotal());
        result.put("pageNum", page.getPageNum());
        result.put("pageSize", page.getPageSize());
        result.put("list", page.getList());
        return result;
    }

    @Tool(name = "assignAgentTask", description = "将任务分配给指定的智能体执行")
    public AgentTaskDTO assignAgentTask(
            @ToolParam(description = "任务ID") String taskId,
            @ToolParam(description = "目标智能体ID") String agentId) {
        AgentTaskAssignDTO request = new AgentTaskAssignDTO();
        request.setAgentId(agentId);
        return agentService.assignTask(taskId, request);
    }

    @Tool(name = "recommendTaskAssignees", description = "根据任务需求推荐最适合执行的智能体列表")
    public List<AgentTaskRecommendationDTO> recommendTaskAssignees(
            @ToolParam(description = "任务ID") String taskId) {
        return agentService.recommendTaskAssignees(taskId);
    }

    @Tool(name = "autoAssignTask", description = "自动将任务分配给系统选出的最佳智能体")
    public AgentTaskDTO autoAssignTask(
            @ToolParam(description = "任务ID") String taskId) {
        return agentService.autoAssignTask(taskId, new AgentTaskAssignDTO());
    }

    @Tool(name = "reportAgentTask", description = "上报任务执行结果，标记任务完成或失败")
    public AgentTaskDTO reportAgentTask(
            @ToolParam(description = "任务ID") String taskId,
            @ToolParam(description = "任务结果状态：completed/failed") String status,
            @ToolParam(description = "当前任务标题/摘要") String currentTaskTitle,
            @ToolParam(description = "失败原因（失败时填写）") String failureReason) {
        AgentTaskReportDTO request = new AgentTaskReportDTO();
        request.setStatus(status);
        request.setCurrentTaskTitle(currentTaskTitle);
        request.setFailureReason(failureReason);
        return agentService.reportTask(taskId, request);
    }

    @Tool(name = "addTaskNote", description = "为任务添加备注/进展记录")
    public AgentTaskNoteDTO addTaskNote(
            @ToolParam(description = "任务ID") String taskId,
            @ToolParam(description = "备注内容") String content) {
        AgentTaskNoteDTO request = new AgentTaskNoteDTO();
        request.setContent(content);
        return agentService.addTaskNote(taskId, request);
    }

    @Tool(name = "listTaskNotes", description = "查看任务的所有备注/进展记录")
    public List<AgentTaskNoteDTO> listTaskNotes(
            @ToolParam(description = "任务ID") String taskId) {
        return agentService.listTaskNotes(taskId);
    }

    @Tool(name = "archiveTask", description = "归档已完成的任务")
    public AgentTaskDTO archiveTask(
            @ToolParam(description = "任务ID") String taskId) {
        return agentService.archiveTask(taskId);
    }

    @Tool(name = "getAgentStats", description = "获取智能体的统计数据，如完成任务数、成功率等")
    public AgentRuntimeDTO getAgentStats(
            @ToolParam(description = "智能体ID") String agentId) {
        return agentService.getStats(agentId);
    }

    @Tool(name = "countTasksByStatus", description = "按状态统计各智能体的任务数量")
    public Map<String, Long> countTasksByStatus() {
        return agentService.countTasksByStatus(new AgentTaskSearchDTO());
    }
}