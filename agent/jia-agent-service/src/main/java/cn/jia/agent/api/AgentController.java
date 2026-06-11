package cn.jia.agent.api;

import cn.jia.agent.entity.AbilityCompareRequestDTO;
import cn.jia.agent.entity.AbilityEvaluationRequestDTO;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentRosterSearchDTO;
import cn.jia.agent.entity.AgentStatusDTO;
import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskReportDTO;
import cn.jia.agent.entity.AgentTaskSearchDTO;
import cn.jia.agent.entity.DialogueRequestDTO;
import cn.jia.agent.service.AbilityEvaluationService;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import com.github.pagehelper.PageInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentController {
    private final AgentService agentService;
    private final AbilityEvaluationService abilityEvaluationService;

    @PostMapping("/register")
    public Object register(@RequestBody AgentRegisterDTO request) {
        return JsonResult.success(agentService.register(request));
    }

    @GetMapping("/list")
    public Object list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String ability,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "50") int pageSize) {
        return page(agentService.list(status, ability, pageNum, pageSize));
    }

    @GetMapping("/map")
    public Object mapAgents() {
        return JsonResult.success(agentService.listMapAgents());
    }

    @PostMapping("/roster")
    public Object roster(@RequestBody AgentRosterSearchDTO request) {
        int pageNum = request.getPageNum() == null ? 1 : request.getPageNum();
        int pageSize = request.getPageSize() == null ? 50 : request.getPageSize();
        return page(agentService.listRoster(request.getStatus(), request.getAbility(), pageNum, pageSize));
    }

    @GetMapping("/{agentId}")
    public Object get(@PathVariable String agentId) {
        return JsonResult.success(agentService.get(agentId));
    }

    @PutMapping("/{agentId}/status")
    public Object updateStatus(@PathVariable String agentId, @RequestBody AgentStatusDTO request) {
        return JsonResult.success(agentService.updateStatus(agentId, request));
    }

    @GetMapping("/{agentId}/tasks")
    public Object getTasks(@PathVariable String agentId) {
        return JsonResult.success(agentService.getAgentTasks(agentId));
    }

    @GetMapping("/personas")
    public Object personas() {
        return JsonResult.success(agentService.listPersonas());
    }

    @GetMapping("/persona/{name}")
    public Object persona(@PathVariable String name) {
        return JsonResult.success(agentService.getPersona(name));
    }

    @PostMapping("/dialogue")
    public Object dialogue(@RequestBody DialogueRequestDTO request) {
        return JsonResult.success(agentService.generateDialogue(request));
    }

    @GetMapping("/stats")
    public Object stats(@RequestParam String agentId) {
        return JsonResult.success(agentService.getStats(agentId));
    }

    @PostMapping("/tasks/search")
    public Object searchTasks(@RequestBody AgentTaskSearchDTO request) {
        return page(agentService.searchTasks(request));
    }

    @PostMapping("/tasks/{taskId}/assign")
    public Object assignTask(@PathVariable String taskId, @RequestBody AgentTaskAssignDTO request) {
        return JsonResult.success(agentService.assignTask(taskId, request));
    }

    @PostMapping("/tasks/{taskId}/report")
    public Object reportTask(@PathVariable String taskId, @RequestBody AgentTaskReportDTO request) {
        return JsonResult.success(agentService.reportTask(taskId, request));
    }

    @PostMapping("/evaluate")
    public Object evaluate(@RequestBody AbilityEvaluationRequestDTO request) {
        return JsonResult.success(abilityEvaluationService.evaluate(request));
    }

    @PostMapping("/compare")
    public Object compare(@RequestBody AbilityCompareRequestDTO request) {
        return JsonResult.success(abilityEvaluationService.compare(request));
    }

    @GetMapping("/evaluation/{agentName}")
    public Object evaluationHistory(@PathVariable String agentName) {
        return JsonResult.success(abilityEvaluationService.history(agentName));
    }

    @GetMapping("/evaluation/latest/{agentName}")
    public Object latestEvaluation(@PathVariable String agentName) {
        return JsonResult.success(abilityEvaluationService.latest(agentName));
    }

    @GetMapping("/evaluation/stats")
    public Object evaluationStats() {
        return JsonResult.success(abilityEvaluationService.stats());
    }

    @DeleteMapping("/evaluation/{id}")
    public Object deleteEvaluation(@PathVariable Long id) {
        abilityEvaluationService.delete(id);
        return JsonResult.success();
    }

    @ExceptionHandler(AgentBizException.class)
    public JsonResult<Void> handleAgentBizException(AgentBizException e) {
        JsonResult<Void> result = JsonResult.failure(e.getCode(), e.getMessage());
        result.setStatus(409);
        return result;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public JsonResult<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        JsonResult<Void> result = JsonResult.failure("BAD_REQUEST", e.getMessage());
        result.setStatus(400);
        return result;
    }

    @ExceptionHandler(Exception.class)
    public JsonResult<Void> handleException(Exception e) {
        log.error("Agent API error", e);
        JsonResult<Void> result = JsonResult.failure("AGENT_ERROR", e.getMessage());
        result.setStatus(500);
        return result;
    }

    private <T> JsonResultPage<T> page(PageInfo<T> pageInfo) {
        JsonResultPage<T> result = JsonResultPage.success(pageInfo.getList());
        result.setPageNum(pageInfo.getPageNum());
        result.setTotal(pageInfo.getTotal());
        return result;
    }
}
