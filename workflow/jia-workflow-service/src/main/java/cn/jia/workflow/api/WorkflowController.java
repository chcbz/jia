package cn.jia.workflow.api;

import cn.jia.core.common.EsHandler;
import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.StreamUtil;
import cn.jia.core.util.StringUtil;
import cn.jia.workflow.entity.DeploymentExample;
import cn.jia.workflow.entity.ProcessDefinitionExample;
import cn.jia.workflow.entity.ProcessInstanceExample;
import cn.jia.workflow.entity.TaskExample;
import cn.jia.workflow.service.WorkflowService;
import com.github.pagehelper.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.history.HistoricTaskInstance;
import org.camunda.bpm.engine.history.HistoricVariableInstance;
import org.camunda.bpm.engine.impl.persistence.entity.SuspensionState;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import jakarta.inject.Inject;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipInputStream;

@RestController
@RequestMapping("/workflow")
public class WorkflowController {

    @Inject
    private WorkflowService workflowService;

    /**
     * 部署工作流
     *
     * @param file
     * @param deployment
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/deploy", method = RequestMethod.POST)
    public Object deployProcess(@RequestPart("file") MultipartFile file, Deployment deployment) throws Exception {
        if (file.getOriginalFilename().endsWith(".zip")) {
            workflowService.deployProcess(deployment, new ZipInputStream(file.getInputStream()));
        } else {
            workflowService.deployProcess(deployment, file.getInputStream());
        }
        return JsonResult.success();
    }

    /**
     * 分页获取工作流列表
     *
     * @param page
     * @return
     */
    @RequestMapping(value = "/deployment/list", method = RequestMethod.POST)
    public Object getDeployment(@RequestBody JsonRequestPage<DeploymentExample> page) {
        DeploymentExample example = Optional.ofNullable(page.getSearch()).orElse(new DeploymentExample());
        Page<Deployment> list = workflowService.getDeployment(example, page.getPageNum(), page.getPageSize());
        List<Map<String, Object>> deployments = new ArrayList<>();
        for (Deployment d : list) {
            Map<String, Object> deployment = new HashMap<>();
            deployment.put("id", d.getId());
            deployment.put("name", d.getName());
            deployment.put("deploymentTime", d.getDeploymentTime());
            deployments.add(deployment);
        }
        JsonResultPage<Map<String, Object>> result = new JsonResultPage<>(deployments);
        result.setPageNum(list.getPageNum());
        result.setTotal(list.getTotal());
        return result;
    }

    /**
     * 根据ID获取工作流
     *
     * @param id
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/deployment/find", method = RequestMethod.GET)
    public Object getDeploymentById(@RequestParam String id) throws Exception {
        Deployment d = workflowService.getDeploymentById(id);
        if (d == null) {
            throw new EsRuntimeException(EsErrorConstants.DATA_NOT_FOUND);
        }
        Map<String, Object> deployment = new HashMap<>();
        deployment.put("id", d.getId());
        deployment.put("name", d.getName());
        deployment.put("deploymentTime", d.getDeploymentTime());
        return JsonResult.success(deployment);
    }

    /**
     * 获取工作流部署资源列表
     *
     * @param id
     * @return
     */
    @RequestMapping(value = "/deployment/resource/list", method = RequestMethod.GET)
    public Object getDeploymentResourceNames(@RequestParam String id) {
        List<String> list = workflowService.getDeploymentResourceNames(id);
        if (list == null || list.size() == 0) {
            throw new EsRuntimeException(EsErrorConstants.DATA_NOT_FOUND);
        }
        return JsonResult.success(list);
    }

    /**
     * 获取工作流部署资源内容
     *
     * @param deploymentId
     * @param resourceName
     * @param request
     * @param response
     * @throws Exception
     */
    @RequestMapping(value = "/deployment/resource/find", method = RequestMethod.GET)
    public void getDeploymentResource(@RequestParam String deploymentId, @RequestParam String resourceName,
									  HttpServletRequest request, HttpServletResponse response) throws Exception {
        Deployment d = workflowService.getDeploymentById(deploymentId);
        if (d == null) {
            throw new EsRuntimeException(EsErrorConstants.DATA_NOT_FOUND);
        }
        response.setCharacterEncoding(request.getCharacterEncoding());
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename=" + resourceName);
        StreamUtil.io(workflowService.getResourceAsStream(deploymentId, resourceName), response.getOutputStream());
        response.flushBuffer();
    }

    /**
     * 删除工作流
     *
     * @param id
     * @return
     */
    @RequestMapping(value = "/deployment/delete", method = RequestMethod.GET)
    public Object deleteDeployment(@RequestParam String id) {
        workflowService.deleteDeployment(id);
        return JsonResult.success();
    }

    /**
     * 获取工作流定义列表
     *
     * @param page
     * @return
     */
    @RequestMapping(value = "/definition/list", method = RequestMethod.POST)
    public Object getDefinition(@RequestBody JsonRequestPage<ProcessDefinitionExample> page) {
        ProcessDefinitionExample example = Optional.ofNullable(page.getSearch()).orElse(new ProcessDefinitionExample());
        Page<ProcessDefinition> list = workflowService.getProcessDefinition(example, page.getPageNum(), page.getPageSize());
        List<Map<String, Object>> deployments = new ArrayList<>();
        for (ProcessDefinition d : list) {
            Map<String, Object> deployment = new HashMap<>();
            deployment.put("category", d.getCategory());
            deployment.put("key", d.getKey());
            deployment.put("id", d.getId());
            deployment.put("name", d.getName());
            deployment.put("deploymentId", d.getDeploymentId());
            deployment.put("resourceName", d.getResourceName());
            deployment.put("version", d.getVersion());
            deployment.put("description", d.getDescription());
            deployment.put("stateCode", d.isSuspended() ? SuspensionState.SUSPENDED.getStateCode() : SuspensionState.ACTIVE.getStateCode());
            deployments.add(deployment);
        }
        JsonResultPage<Map<String, Object>> result = new JsonResultPage<>(deployments);
        result.setPageNum(list.getPageNum());
        result.setTotal(list.getTotal());
        return result;
    }

    /**
     * 根据ID获取工作流定义信息
     *
     * @param id
     * @return
	 */
    @RequestMapping(value = "/definition/find", method = RequestMethod.GET)
    public Object getDefinitionById(@RequestParam String id) {
        ProcessDefinition d = workflowService.getProcessDefinitionById(id);
        if (d == null) {
            throw new EsRuntimeException(EsErrorConstants.DATA_NOT_FOUND);
        }
        Map<String, Object> definition = new HashMap<>();
        definition.put("category", d.getCategory());
        definition.put("key", d.getKey());
        definition.put("id", d.getId());
        definition.put("name", d.getName());
        definition.put("deploymentId", d.getDeploymentId());
        definition.put("diagramResourceName", d.getDiagramResourceName());
        definition.put("resourceName", d.getResourceName());
        definition.put("version", d.getVersion());
        definition.put("description", d.getDescription());
        definition.put("stateCode", d.isSuspended() ? SuspensionState.SUSPENDED.getStateCode() : SuspensionState.ACTIVE.getStateCode());
        return JsonResult.success(definition);
    }

    /**
     * 获取工作流图解内容
     *
     * @param id
     * @param request
     * @param response
     * @throws Exception
     */
    @RequestMapping(value = "/definition/diagram", method = RequestMethod.GET)
    public void getProcessDiagram(@RequestParam String id, HttpServletRequest request,
                                  HttpServletResponse response) throws Exception {
        ProcessDefinition d = workflowService.getProcessDefinitionById(id);
        if (d == null) {
            throw new EsRuntimeException(EsErrorConstants.DATA_NOT_FOUND);
        }
        response.setCharacterEncoding(request.getCharacterEncoding());
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename=" + d.getDiagramResourceName());
        StreamUtil.io(workflowService.getProcessDiagram(id), response.getOutputStream());
        response.flushBuffer();
    }

    /**
     * 激活工作流
     *
     * @param id
     * @return
     */
    @RequestMapping(value = "/definition/activate", method = RequestMethod.GET)
    public Object activateDefinition(@RequestParam String id) {
        workflowService.activateProcessDefinition(id);
        return JsonResult.success();
    }

    /**
     * 挂起工作流
     *
     * @param id
     * @return
     */
    @RequestMapping(value = "/definition/suspend", method = RequestMethod.GET)
    public Object suspendDefinition(@RequestParam String id) {
        workflowService.suspendProcessDefinition(id);
        return JsonResult.success();
    }

    /**
     * 开始流程实例
     *
     * @param processDefinitionKey 工作流名称
     * @param businessKey          业务编号
     * @param variables            参数
     * @return
     */
    @PostMapping(value = "/start/{processDefinitionKey}/{businessKey}")
    public Object startProcess(@PathVariable String processDefinitionKey, 
							   @PathVariable String businessKey, 
							   @RequestBody Map<String, Object> variables) {
        variables.put("businessKey", businessKey);
        workflowService.startProcess(processDefinitionKey, businessKey, variables);
        return JsonResult.success();
    }

    /**
     * 根据实例ID删除流程实例
     *
     * @param processInstanceId
     * @param deleteReason
     * @return
     */
    @PostMapping(value = "/delete/{processInstanceId}")
    public Object deleteProcess(@PathVariable String processInstanceId, @RequestParam String deleteReason) {
        workflowService.deleteProcessInstance(processInstanceId, deleteReason);
        return JsonResult.success();
    }

    /**
     * 获得某个人的任务别表
     *
     * @param page
     * @return
     */
    @PostMapping(value = "/list/wait")
    public Object listWait(@RequestBody JsonRequestPage<TaskExample> page) {
        TaskExample example = Optional.ofNullable(page.getSearch()).orElse(new TaskExample());
        Page<Task> taskList = workflowService.getTasks(example, page.getPageNum(), page.getPageSize());
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (Task task : taskList) {
            Map<String, Object> t = new HashMap<>();
            t.put("id", task.getId());
            t.put("name", task.getName());
            t.put("taskDefinitionKey", task.getTaskDefinitionKey());
            t.put("assignee", task.getAssignee());
            t.put("processInstanceId", task.getProcessInstanceId());

            t.put("applicant", workflowService.getProcessVariables(task.getId(), "applicant"));
            t.put("applicationDate", workflowService.getProcessVariables(task.getId(), "applicationDate"));

            t.put("candidate", String.join(",", workflowService.getCandidate(task.getId())));

            ProcessInstance pi = workflowService.getProcessInstanceByTask(task);
            t.put("businessKey", pi.getBusinessKey());
            ProcessDefinition pd = workflowService.getProcessDefinitionById(pi.getProcessDefinitionId());
            t.put("definitionKey", pd.getKey());
            t.put("definitionName", pd.getName());
            tasks.add(t);
        }
        JsonResultPage<Map<String, Object>> result = new JsonResultPage<>(tasks);
        result.setPageNum(taskList.getPageNum());
        result.setTotal(taskList.getTotal());
        return result;
    }

    /**
     * 获取历史审批
     *
     * @param page
     * @return
     */
    @PostMapping(value = "/list/history")
    public Object listHistory(@RequestBody JsonRequestPage<TaskExample> page) {
        TaskExample example = Optional.ofNullable(page.getSearch()).orElse(new TaskExample());
        Page<HistoricTaskInstance> taskList = workflowService.getHistorys(example, page.getPageNum(), page.getPageSize());
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (HistoricTaskInstance task : taskList) {
            Map<String, Object> t = new HashMap<>();
            t.put("id", task.getId());
            t.put("name", task.getName());
            t.put("taskDefinitionKey", task.getTaskDefinitionKey());
            t.put("assignee", task.getAssignee());
            t.put("processInstanceId", task.getProcessInstanceId());

            t.put("applicant", workflowService.getHistoricVariable(task.getProcessInstanceId(), "applicant"));
            t.put("applicationDate", workflowService.getHistoricVariable(task.getProcessInstanceId(), "applicationDate"));

            HistoricProcessInstance pi = workflowService.getHistoricProcessInstanceById(task.getProcessInstanceId());
            t.put("businessKey", pi.getBusinessKey());
            t.put("definitionKey", pi.getProcessDefinitionKey());
            t.put("definitionName", pi.getProcessDefinitionName());
            tasks.add(t);
        }
        JsonResultPage<Map<String, Object>> result = new JsonResultPage<>(tasks);
        result.setPageNum(taskList.getPageNum());
        result.setTotal(taskList.getTotal());
        return result;
    }

    /**
     * 获取处理过程
     *
     * @param page
     * @return
     */
    @PostMapping(value = "/list/process")
    public Object listHistoryByBusinessKey(@RequestBody JsonRequestPage<String> page) {
        Page<HistoricTaskInstance> taskList =
				workflowService.getHistorysByBusinessKey(page.getSearch(), page.getPageNum(), page.getPageSize());
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (HistoricTaskInstance task : taskList) {
            Map<String, Object> t = new HashMap<>();
            t.put("id", task.getId());
            t.put("name", task.getName());
            t.put("taskDefinitionKey", task.getTaskDefinitionKey());
            t.put("assignee", task.getAssignee());
            t.put("processInstanceId", task.getProcessInstanceId());
            t.put("processTime", task.getEndTime());
            t.put("comment", workflowService.getTaskComment(task.getId()));

            List<Map<String, Object>> vars = new ArrayList<>();
            List<HistoricVariableInstance> varList =
					workflowService.getHistoricVariables(task.getProcessInstanceId(), task.getId());
            for (HistoricVariableInstance var : varList) {
                Map<String, Object> v = new HashMap<>();
                v.put("name", var.getName());
                v.put("value", var.getValue());
                v.put("type", var.getTypeName());
                vars.add(v);
            }
            t.put("variables", vars);
            tasks.add(t);
        }
        JsonResultPage<Map<String, Object>> result = new JsonResultPage<>(tasks);
        result.setPageNum(taskList.getPageNum());
        result.setTotal(taskList.getTotal());
        return result;
    }

    /**
     * 获取我的申请列表
     *
     * @param page
     * @param request
     * @return
     */
    @PostMapping(value = "/list/my")
    public Object listHistoricProcessInstances(@RequestBody JsonRequestPage<ProcessInstanceExample> page, HttpServletRequest request) {
        ProcessInstanceExample example = Optional.ofNullable(page.getSearch()).orElse(new ProcessInstanceExample());
        Page<HistoricProcessInstance> instanceList =
				workflowService.getHistoricProcessInstances(example, page.getPageNum(), page.getPageSize());
        List<Map<String, Object>> instances = new ArrayList<>();
        for (HistoricProcessInstance instance : instanceList) {
            Map<String, Object> t = new HashMap<>();

            t.put("id", instance.getId());
            t.put("definitionKey", instance.getProcessDefinitionKey());
            t.put("definitionName", instance.getProcessDefinitionName());
            t.put("applicant", instance.getStartUserId());
            t.put("startTime", instance.getStartTime());
            t.put("endTime", instance.getEndTime());
            t.put("businessKey", instance.getBusinessKey());
            if (instance.getEndTime() == null) {
                List<Task> tasks = workflowService.getTaskByProcessInstanceId(instance.getId());
                StringBuilder assignee = new StringBuilder();
                String name = "";
                List<String> candidate = new ArrayList<>();
                for (Task task : tasks) {
                    assignee.append(",").append(task.getAssignee());
                    name = task.getName();
                    candidate.addAll(workflowService.getCandidate(task.getId()));
                }
                t.put("assignee", "".equals(assignee.toString()) ? "" : assignee.substring(1));
                t.put("candidate", candidate.size() == 0 ? "" : String.join(",", candidate));
                t.put("name", name);
            } else {
                t.put("assignee", "");
                t.put("name", EsHandler.getMessage(request, "status.finished"));
            }

            instances.add(t);
        }
        JsonResultPage<Map<String, Object>> result = new JsonResultPage<>(instances);
        result.setPageNum(instanceList.getPageNum());
        result.setTotal(instanceList.getTotal());
        return result;
    }

    /**
     * 完成任务
     *
     * @param taskId
     * @return
     */
    @SuppressWarnings("unchecked")
    @PostMapping(value = "/complete/{taskId}")
    public Object complete(@PathVariable String taskId, @RequestBody Map<String, Object> data) {
        Task task = workflowService.getTaskById(taskId);
        if (task == null) {
            throw new EsRuntimeException(EsErrorConstants.DATA_NOT_FOUND);
        }
        Map<String, Object> variables = null;
        if (data != null) {
            variables = (Map<String, Object>) data.get("variables");
            String comment = (String) data.get("comment");
            if (StringUtil.isNotEmpty(comment)) {
                workflowService.addComment(taskId, comment);
            }
            Map<String, Object> taskVariables = (Map<String, Object>) data.get("taskVariables");
            if (taskVariables != null) {
                workflowService.setTaskVariables(taskId, taskVariables);
            }
        }
        workflowService.completeTasks(taskId, variables);
        return JsonResult.success();
    }

    /**
     * 委托受理人
     *
     * @param taskId 任务ID
     * @param userId 被委托人
     */
    @GetMapping(value = "/task/delegate")
    public Object delegateTask(@RequestParam String taskId, @RequestParam String userId) {
        workflowService.delegateTask(taskId, userId);
        return JsonResult.success();
    }

    /**
     * 任务认领
     *
     * @param taskId 任务ID
     * @param userId 认领人ID
     */
    @GetMapping(value = "/task/claim")
    public Object claimTask(String taskId, String userId) {
        workflowService.claimTask(taskId, userId);
        return JsonResult.success();
    }

    /**
     * 任务指派
     *
     * @param taskId
     * @param userId
     * @return
     */
    @GetMapping(value = "/task/assign")
    public Object setAssignee(String taskId, String userId) {
        workflowService.setAssignee(taskId, userId);
        return JsonResult.success();
    }

    /**
     * 根据业务编号获取任务信息
     *
     * @param businessKey
     * @param assignee
     * @return
	 */
    @GetMapping(value = "/find/bybusinesskey")
    public Object getTaskByBusinessKey(@RequestParam String businessKey, @RequestParam String assignee) {
        Task task = workflowService.getTaskByBusinessKey(businessKey, assignee);
        if (task == null) {
            throw new EsRuntimeException(EsErrorConstants.DATA_NOT_FOUND);
        }
        Map<String, Object> t = new HashMap<>();
        t.put("id", task.getId());
        t.put("name", task.getName());
        t.put("taskDefinitionKey", task.getTaskDefinitionKey());
        t.put("assignee", task.getAssignee());
        t.put("processInstanceId", task.getProcessInstanceId());

        t.put("candidate", String.join(",", workflowService.getCandidate(task.getId())));

        t.put("applicant", workflowService.getProcessVariables(task.getId(), "applicant"));
        t.put("applicationDate", workflowService.getProcessVariables(task.getId(), "applicationDate"));

        ProcessInstance pi = workflowService.getProcessInstanceByTask(task);
        t.put("businessKey", pi.getBusinessKey());
        ProcessDefinition pd = workflowService.getProcessDefinitionById(pi.getProcessDefinitionId());
        t.put("definitionKey", pd.getKey());
        t.put("definitionName", pd.getName());
        return JsonResult.success(t);
    }

    /**
     * 根据业务编码获取最新任务列表
     *
     * @param businessKey
     * @return
	 */
    @GetMapping(value = "/list/bybusinesskey")
    public Object listTaskByBusinessKey(@RequestParam String businessKey) {
        List<Task> tasks = workflowService.getTaskByBusinessKey(businessKey);
        if (tasks == null || tasks.size() == 0) {
            throw new EsRuntimeException(EsErrorConstants.DATA_NOT_FOUND);
        }
        List<Map<String, Object>> taskList = new ArrayList<>();
        for (Task task : tasks) {
            Map<String, Object> t = new HashMap<>();
            t.put("id", task.getId());
            t.put("name", task.getName());
            t.put("taskDefinitionKey", task.getTaskDefinitionKey());
            t.put("assignee", task.getAssignee());
            t.put("candidate", String.join(",", workflowService.getCandidate(task.getId())));
            t.put("processInstanceId", task.getProcessInstanceId());
            taskList.add(t);
        }

        return JsonResult.success(taskList);
    }

    /**
     * 根据任务ID获取任务信息
     *
     * @param taskId
     * @return
	 */
    @GetMapping(value = "/find")
    public Object getTaskById(@RequestParam String taskId) {
        Task task = workflowService.getTaskById(taskId);
        if (task == null) {
            throw new EsRuntimeException(EsErrorConstants.DATA_NOT_FOUND);
        }
        Map<String, Object> t = new HashMap<>();
        t.put("id", task.getId());
        t.put("name", task.getName());
        t.put("taskDefinitionKey", task.getTaskDefinitionKey());
        t.put("assignee", task.getAssignee());
        t.put("processInstanceId", task.getProcessInstanceId());

        t.put("candidate", String.join(",", workflowService.getCandidate(task.getId())));

        t.put("applicant", workflowService.getProcessVariables(task.getId(), "applicant"));
        t.put("applicationDate", workflowService.getProcessVariables(task.getId(), "applicationDate"));

        ProcessInstance pi = workflowService.getProcessInstanceByTask(task);
        t.put("businessKey", pi.getBusinessKey());
        ProcessDefinition pd = workflowService.getProcessDefinitionById(pi.getProcessDefinitionId());
        t.put("definitionKey", pd.getKey());
        t.put("definitionName", pd.getName());
        return JsonResult.success(t);
    }

    /**
     * 获取实例流程图
     *
     * @param id
     * @param request
     * @param response
     * @throws Exception
     */
    @RequestMapping(value = "/instance/diagram", method = RequestMethod.GET)
    public void getInstanceDiagram(@RequestParam String id, HttpServletRequest request,
                                   HttpServletResponse response) throws Exception {
        HistoricProcessInstance d = workflowService.getHistoricProcessInstanceById(id);
        if (d == null) {
            throw new EsRuntimeException(EsErrorConstants.DATA_NOT_FOUND);
        }
        response.setCharacterEncoding(request.getCharacterEncoding());
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename=" + d.getProcessDefinitionKey() + "_" + id + ".png");
        StreamUtil.io(workflowService.getInstanceDiagram(id), response.getOutputStream());
        response.flushBuffer();
    }
}
