package cn.jia.workflow.api;

import cn.jia.core.common.EsHandler;
import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.JSONRequestPage;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.entity.JSONResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.JSONUtil;
import cn.jia.core.util.StreamUtil;
import cn.jia.core.util.StringUtils;
import cn.jia.workflow.common.ErrorConstants;
import cn.jia.workflow.entity.DeploymentExample;
import cn.jia.workflow.entity.ProcessDefinitionExample;
import cn.jia.workflow.entity.ProcessInstanceExample;
import cn.jia.workflow.entity.TaskExample;
import cn.jia.workflow.service.WorkflowService;
import com.github.pagehelper.Page;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.history.HistoricTaskInstance;
import org.camunda.bpm.engine.history.HistoricVariableInstance;
import org.camunda.bpm.engine.impl.persistence.entity.SuspensionState;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

@RestController
@RequestMapping("/workflow")
public class WorkflowController {

	@Autowired
	private WorkflowService workflowService;

	/**
	 * 部署工作流
	 * @param file
	 * @param deployment
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('workflow-deploy')")
	@RequestMapping(value = "/deploy", method = RequestMethod.POST)
	public Object deployProcess(@RequestPart("file") MultipartFile file, Deployment deployment) throws Exception {
		if(file.getOriginalFilename().endsWith(".zip")) {
			workflowService.deployProcess(deployment, new ZipInputStream(file.getInputStream()), EsSecurityHandler.clientId());
		}else {
			workflowService.deployProcess(deployment, file.getInputStream(), EsSecurityHandler.clientId());
		}
		return JSONResult.success();
	}

	/**
	 * 分页获取工作流列表
	 * @param page
	 * @param auth
	 * @return
	 */
	@PreAuthorize("hasAuthority('workflow-deployment_list')")
	@RequestMapping(value = "/deployment/list", method = RequestMethod.POST)
	public Object getDeployment(@RequestBody JSONRequestPage<String> page, OAuth2Authentication auth) {
		DeploymentExample example = JSONUtil.fromJson(page.getSearch(), DeploymentExample.class);
		Page<Deployment> list = workflowService.getDeployment(example, page.getPageNum(), page.getPageSize(), EsSecurityHandler.clientId());
		List<Map<String, Object>> deployments = new ArrayList<>();
		for(Deployment d : list) {
			Map<String, Object> deployment = new HashMap<>();
			deployment.put("id", d.getId());
			deployment.put("name", d.getName());
			deployment.put("deploymentTime", d.getDeploymentTime());
			deployments.add(deployment);
		}
		JSONResultPage<Object> result = new JSONResultPage<>(deployments);
		result.setPageNum(list.getPageNum());
		result.setTotal(list.getTotal());
		return result;
	}

	/**
	 * 根据ID获取工作流
	 * @param id
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('workflow-deployment_find')")
	@RequestMapping(value = "/deployment/find", method = RequestMethod.GET)
	public Object getDeploymentById(@RequestParam String id) throws Exception {
		Deployment d = workflowService.getDeploymentById(id, EsSecurityHandler.clientId());
		if(d == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		Map<String, Object> deployment = new HashMap<>();
		deployment.put("id", d.getId());
		deployment.put("name", d.getName());
		deployment.put("deploymentTime", d.getDeploymentTime());
		return JSONResult.success(deployment);
	}
	
	/**
	 * 获取工作流部署资源列表
	 * @param id
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('workflow-deployment_resource_list')")
	@RequestMapping(value = "/deployment/resource/list", method = RequestMethod.GET)
	public Object getDeploymentResourceNames(@RequestParam String id) throws Exception {
		List<String> list = workflowService.getDeploymentResourceNames(id);
		if (list == null || list.size() == 0) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		return JSONResult.success(list);
	}
	
	/**
	 * 获取工作流部署资源内容
	 * @param deploymentId
	 * @param resourceName
	 * @param request
	 * @param response
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('workflow-deployment_resource_find')")
	@RequestMapping(value = "/deployment/resource/find", method = RequestMethod.GET)
	public void getDeploymentResource(@RequestParam String deploymentId, @RequestParam String resourceName,
			HttpServletRequest request, HttpServletResponse response) throws Exception {
		Deployment d = workflowService.getDeploymentById(deploymentId, EsSecurityHandler.clientId());
		if (d == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		response.setCharacterEncoding(request.getCharacterEncoding());
		response.setContentType("application/octet-stream");
		response.setHeader("Content-Disposition", "attachment; filename=" + resourceName);
		StreamUtil.io(workflowService.getResourceAsStream(deploymentId, resourceName), response.getOutputStream());
		response.flushBuffer();
	}

	/**
	 * 删除工作流
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('workflow-deployment_delete')")
	@RequestMapping(value = "/deployment/delete", method = RequestMethod.GET)
	public Object deleteDeployment(@RequestParam String id) {
		workflowService.deleteDeployment(id);
		return JSONResult.success();
	}
	
	/**
	 * 获取工作流定义列表
	 * @param page
	 * @return
	 */
	@PreAuthorize("hasAuthority('workflow-definition_list')")
	@RequestMapping(value = "/definition/list", method = RequestMethod.POST)
	public Object getDefinition(@RequestBody JSONRequestPage<String> page) {
		ProcessDefinitionExample example = JSONUtil.fromJson(page.getSearch(), ProcessDefinitionExample.class);
		Page<ProcessDefinition> list = workflowService.getProcessDefinition(example, page.getPageNum(), page.getPageSize(), EsSecurityHandler.clientId());
		List<Map<String, Object>> deployments = new ArrayList<>();
		for(ProcessDefinition d : list) {
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
		JSONResultPage<Object> result = new JSONResultPage<>(deployments);
		result.setPageNum(list.getPageNum());
		result.setTotal(list.getTotal());
		return result;
	}

	/**
	 * 根据ID获取工作流定义信息
	 * @param id
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('workflow-definition_find')")
	@RequestMapping(value = "/definition/find", method = RequestMethod.GET)
	public Object getDefinitionById(@RequestParam String id) throws Exception {
		ProcessDefinition d = workflowService.getProcessDefinitionById(id);
		if(d == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
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
		return JSONResult.success(definition);
	}
	
	/**
	 * 获取工作流图解内容
	 * @param id
	 * @param request
	 * @param response
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('workflow-definition_diagram')")
	@RequestMapping(value = "/definition/diagram", method = RequestMethod.GET)
	public void getProcessDiagram(@RequestParam String id, HttpServletRequest request,
			HttpServletResponse response) throws Exception {
		ProcessDefinition d = workflowService.getProcessDefinitionById(id);
		if(d == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		response.setCharacterEncoding(request.getCharacterEncoding());
		response.setContentType("application/octet-stream");
		response.setHeader("Content-Disposition", "attachment; filename=" + d.getDiagramResourceName());
		StreamUtil.io(workflowService.getProcessDiagram(id), response.getOutputStream());
		response.flushBuffer();
	}

	/**
	 * 激活工作流
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('workflow-definition_activate')")
	@RequestMapping(value = "/definition/activate", method = RequestMethod.GET)
	public Object activateDefinition(@RequestParam String id) {
		workflowService.activateProcessDefinition(id);
		return JSONResult.success();
	}

	/**
	 * 挂起工作流
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('workflow-definition_suspend')")
	@RequestMapping(value = "/definition/suspend", method = RequestMethod.GET)
	public Object suspendDefinition(@RequestParam String id) {
		workflowService.suspendProcessDefinition(id);
		return JSONResult.success();
	}

	/**
	 * 开始流程实例
	 * @param processDefinitionKey 工作流名称
	 * @param businessKey 业务编号
	 * @param variables 参数
	 * @return
	 */
	@PreAuthorize("hasAuthority('workflow-start')")
	@RequestMapping(value = "/start/{processDefinitionKey}/{businessKey}", method = RequestMethod.POST)
	public Object startProcess(@PathVariable String processDefinitionKey, @PathVariable String businessKey, @RequestBody Map<String,Object> variables) {
		variables.put("businessKey", businessKey);
		workflowService.startProcess(processDefinitionKey, businessKey, variables, EsSecurityHandler.clientId());
		return JSONResult.success();
	}
	
	/**
	 * 根据实例ID删除流程实例
	 * @param processInstanceId
	 * @param deleteReason
	 * @return
	 */
	@PreAuthorize("hasAuthority('workflow-delete')")
	@RequestMapping(value = "/delete/{processInstanceId}", method = RequestMethod.POST)
	public Object deleteProcess(@PathVariable String processInstanceId, @RequestParam String deleteReason) {
		workflowService.deleteProcessInstance(processInstanceId, deleteReason);
		return JSONResult.success();
	}

	/**
	 * 获得某个人的任务别表
	 * @param page
	 * @return
	 */
	@PreAuthorize("hasAuthority('workflow-task_list')")
	@RequestMapping(value = "/task/list", method = RequestMethod.POST)
	public Object listTask(@RequestBody JSONRequestPage<String> page) {
		TaskExample example = JSONUtil.fromJson(page.getSearch(), TaskExample.class);
		Page<Task> taskList = workflowService.getTasks(example, page.getPageNum(), page.getPageSize(), EsSecurityHandler.clientId());
		List<Map<String, Object>> tasks = new ArrayList<>();
		for(Task task : taskList) {
			Map<String, Object> t = new HashMap<>();
			t.put("id", task.getId());
			t.put("name", task.getName());
			t.put("taskDefinitionKey", task.getTaskDefinitionKey());
			t.put("assignee", task.getAssignee());
			t.put("processInstanceId", task.getProcessInstanceId());
			
			t.put("applicant", workflowService.getProcessVariables(task.getId(), "applicant"));
			t.put("applicationDate", workflowService.getProcessVariables(task.getId(), "applicationDate"));
			
			ProcessInstance pi = workflowService.getProcessInstanceByTask(task, EsSecurityHandler.clientId());
			t.put("businessKey", pi.getBusinessKey());
			ProcessDefinition pd = workflowService.getProcessDefinitionById(pi.getProcessDefinitionId());
			t.put("definitionKey", pd.getKey());
			t.put("definitionName", pd.getName());
			tasks.add(t);
		}
		JSONResultPage<Object> result = new JSONResultPage<>(tasks);
		result.setPageNum(taskList.getPageNum());
		result.setTotal(taskList.getTotal());
		return result;
	}

	/**
	 * 获取历史审批
	 * @param page
	 * @return
	 */
	@PreAuthorize("hasAuthority('workflow-list_history')")
	@RequestMapping(value = "/list/history", method = RequestMethod.POST)
	public Object listHistory(@RequestBody JSONRequestPage<String> page) {
		TaskExample example = JSONUtil.fromJson(page.getSearch(), TaskExample.class);
		Page<HistoricTaskInstance> taskList = workflowService.getHistorys(example, page.getPageNum(), page.getPageSize(), EsSecurityHandler.clientId());
		List<Map<String, Object>> tasks = new ArrayList<>();
		for(HistoricTaskInstance task : taskList) {
			Map<String, Object> t = new HashMap<>();
			t.put("id", task.getId());
			t.put("name", task.getName());
			t.put("taskDefinitionKey", task.getTaskDefinitionKey());
			t.put("assignee", task.getAssignee());
			t.put("processInstanceId", task.getProcessInstanceId());
			
			t.put("applicant", workflowService.getHistoricVariable(task.getProcessInstanceId(), "applicant"));
			t.put("applicationDate", workflowService.getHistoricVariable(task.getProcessInstanceId(), "applicationDate"));
			
			HistoricProcessInstance pi = workflowService.getHistoricProcessInstanceById(task.getProcessInstanceId(), EsSecurityHandler.clientId());
			t.put("businessKey", pi.getBusinessKey());
			t.put("definitionKey", pi.getProcessDefinitionKey());
			t.put("definitionName", pi.getProcessDefinitionName());
			tasks.add(t);
		}
		JSONResultPage<Object> result = new JSONResultPage<>(tasks);
		result.setPageNum(taskList.getPageNum());
		result.setTotal(taskList.getTotal());
		return result;
	}

	/**
	 * 获取处理过程
	 * @param page
	 * @return
	 */
	@PreAuthorize("hasAuthority('workflow-list_process')")
	@RequestMapping(value = "/list/process", method = RequestMethod.POST)
	public Object listHistoryByBusinessKey(@RequestBody JSONRequestPage<String> page) {
		Page<HistoricTaskInstance> taskList = workflowService.getHistorysByBusinessKey(page.getSearch(), page.getPageNum(), page.getPageSize(), EsSecurityHandler.clientId());
		List<Map<String, Object>> tasks = new ArrayList<>();
		for(HistoricTaskInstance task : taskList) {
			Map<String, Object> t = new HashMap<>();
			t.put("id", task.getId());
			t.put("name", task.getName());
			t.put("taskDefinitionKey", task.getTaskDefinitionKey());
			t.put("assignee", task.getAssignee());
			t.put("processInstanceId", task.getProcessInstanceId());
			t.put("processTime", task.getEndTime());
			t.put("comment", workflowService.getTaskComment(task.getId()));
			
			List<Map<String, Object>> vars = new ArrayList<>();
			List<HistoricVariableInstance> varList = workflowService.getHistoricVariables(task.getProcessInstanceId(), task.getId());
			for(HistoricVariableInstance var : varList) {
				Map<String, Object> v = new HashMap<>();
				v.put("name", var.getName());
				v.put("value", var.getValue());
				v.put("type", var.getTypeName());
				vars.add(v);
			}
			t.put("variables", vars);
			tasks.add(t);
		}
		JSONResultPage<Object> result = new JSONResultPage<>(tasks);
		result.setPageNum(taskList.getPageNum());
		result.setTotal(taskList.getTotal());
		return result;
	}

	/**
	 * 获取实例列表
	 * @param page
	 * @param request
	 * @return
	 */
	@PreAuthorize("hasAuthority('workflow-list_instance')")
	@RequestMapping(value = "/list/instance", method = RequestMethod.POST)
	public Object listHistoricProcessInstances(@RequestBody JSONRequestPage<String> page, HttpServletRequest request) {
		ProcessInstanceExample example = JSONUtil.fromJson(page.getSearch(), ProcessInstanceExample.class);
		Page<HistoricProcessInstance> instanceList = workflowService.getHistoricProcessInstances(example, page.getPageNum(), page.getPageSize(), EsSecurityHandler.clientId());
		List<Map<String, Object>> instances = new ArrayList<>();
		for(HistoricProcessInstance instance : instanceList) {
			Map<String, Object> t = new HashMap<>();
			
			t.put("id", instance.getId());
			t.put("definitionKey", instance.getProcessDefinitionKey());
			t.put("definitionName", instance.getProcessDefinitionName());
			t.put("applicant", instance.getStartUserId());
			t.put("startTime", instance.getStartTime());
			t.put("endTime", instance.getEndTime());
			t.put("businessKey", instance.getBusinessKey());
			if(instance.getEndTime() == null) {
				List<Task> tasks = workflowService.getTaskByProcessInstanceId(instance.getId(), EsSecurityHandler.clientId());
				String assignee = "";
				String candidate = "";
				String name = "";
				for(Task task : tasks) {
					assignee += "," + task.getAssignee();
					name = task.getName();
					candidate += "," + org.apache.commons.lang3.StringUtils.join(workflowService.getCandidate(task.getId()).toArray(), ",");
				}
				t.put("assignee", "".equals(assignee) ? "" : assignee.substring(1));
				t.put("candidate", "".equals(candidate) ? "" : candidate.substring(1));
				t.put("name", name);
			}else {
				t.put("assignee", "");
				t.put("candidate", "");
				t.put("name", EsHandler.getMessage(request, "status.finished"));
			}
			
			instances.add(t);
		}
		JSONResultPage<Object> result = new JSONResultPage<>(instances);
		result.setPageNum(instanceList.getPageNum());
		result.setTotal(instanceList.getTotal());
		return result;
	}

	/**
	 * 完成任务
	 * @param taskId
	 * @return
	 */
	@SuppressWarnings("unchecked")
	@PreAuthorize("hasAuthority('workflow-complete')")
	@RequestMapping(value = "/complete/{taskId}", method = RequestMethod.POST)
	public Object complete(@PathVariable String taskId, @RequestBody Map<String,Object> data) {
		Map<String, Object> variables = null;
		if(data != null) {
			variables = (Map<String, Object>)data.get("variables");
			String comment = (String)data.get("comment");
			if(StringUtils.isNotEmpty(comment)) {
				workflowService.addComment(taskId, comment);
			}
			Map<String, Object> taskVariables = (Map<String, Object>)data.get("taskVariables");
			if(taskVariables != null) {
				workflowService.setTaskVariables(taskId, taskVariables);
			}
		}
		workflowService.completeTasks(taskId, variables);
		return JSONResult.success();
	}

	/**
	 * 委托受理人
	 * @param taskId 任务ID
	 * @param userId 被委托人
	 */
	@PreAuthorize("hasAuthority('workflow-task_delegate')")
	@RequestMapping(value = "/task/delegate", method = RequestMethod.GET)
	public Object delegateTask(@RequestParam String taskId, @RequestParam String userId) {
		workflowService.delegateTask(taskId, userId);
		return JSONResult.success();
	}
	
	/**
	 * 任务认领
	 * @param taskId 任务ID
	 * @param userId 认领人ID
	 */
	@PreAuthorize("hasAuthority('workflow-task_claim')")
	@RequestMapping(value = "/task/claim", method = RequestMethod.GET)
	public Object claimTask(String taskId, String userId) {
		workflowService.claimTask(taskId, userId);
		return JSONResult.success();
	}
	
	/**
	 * 任务指派
	 * @param taskId
	 * @param userId
	 * @return
	 */
	@PreAuthorize("hasAuthority('workflow-task_assign')")
	@RequestMapping(value = "/task/assign", method = RequestMethod.GET)
	public Object setAssignee(String taskId, String userId) {
		workflowService.setAssignee(taskId, userId);
		return JSONResult.success();
	}
	
	/**
	 * 根据业务编号获取任务信息
	 * @param businessKey
	 * @param assignee
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('workflow-find_bybusinesskey')")
	@RequestMapping(value = "/find/bybusinesskey", method = RequestMethod.GET)
	public Object getTaskByBusinessKey(@RequestParam String businessKey, @RequestParam String assignee) throws Exception {
		Task task = workflowService.getTaskByBusinessKey(businessKey, assignee, EsSecurityHandler.clientId());
		if(task == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		Map<String, Object> t = new HashMap<>();
		t.put("id", task.getId());
		t.put("name", task.getName());
		t.put("taskDefinitionKey", task.getTaskDefinitionKey());
		t.put("assignee", task.getAssignee());
		t.put("processInstanceId", task.getProcessInstanceId());
		
		t.put("applicant", workflowService.getProcessVariables(task.getId(), "applicant"));
		t.put("applicationDate", workflowService.getProcessVariables(task.getId(), "applicationDate"));
		
		ProcessInstance pi = workflowService.getProcessInstanceByTask(task, EsSecurityHandler.clientId());
		t.put("businessKey", pi.getBusinessKey());
		ProcessDefinition pd = workflowService.getProcessDefinitionById(pi.getProcessDefinitionId());
		t.put("definitionKey", pd.getKey());
		t.put("definitionName", pd.getName());
		return JSONResult.success(t);
	}
	
	/**
	 * 根据业务编码获取最新任务列表
	 * @param businessKey
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('workflow-list_bybusinesskey')")
	@RequestMapping(value = "/list/bybusinesskey", method = RequestMethod.GET)
	public Object listTaskByBusinessKey(@RequestParam String businessKey) throws Exception {
		List<Task> tasks = workflowService.getTaskByBusinessKey(businessKey, EsSecurityHandler.clientId());
		if(tasks == null || tasks.size() == 0) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		List<Map<String, Object>> taskList = new ArrayList<>();
		for(Task task : tasks) {
			Map<String, Object> t = new HashMap<>();
			t.put("id", task.getId());
			t.put("name", task.getName());
			t.put("taskDefinitionKey", task.getTaskDefinitionKey());
			t.put("assignee", task.getAssignee());
			t.put("processInstanceId", task.getProcessInstanceId());
			taskList.add(t);
		}
		
		return JSONResult.success(taskList);
	}
	
	/**
	 * 根据任务ID获取任务信息
	 * @param taskId
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('workflow-find')")
	@RequestMapping(value = "/find", method = RequestMethod.GET)
	public Object getTaskById(@RequestParam String taskId) throws Exception {
		Task task = workflowService.getTaskById(taskId, EsSecurityHandler.clientId());
		if(task == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		Map<String, Object> t = new HashMap<>();
		t.put("id", task.getId());
		t.put("name", task.getName());
		t.put("taskDefinitionKey", task.getTaskDefinitionKey());
		t.put("assignee", task.getAssignee());
		t.put("processInstanceId", task.getProcessInstanceId());
		
		t.put("applicant", workflowService.getProcessVariables(task.getId(), "applicant"));
		t.put("applicationDate", workflowService.getProcessVariables(task.getId(), "applicationDate"));
		
		ProcessInstance pi = workflowService.getProcessInstanceByTask(task, EsSecurityHandler.clientId());
		t.put("businessKey", pi.getBusinessKey());
		ProcessDefinition pd = workflowService.getProcessDefinitionById(pi.getProcessDefinitionId());
		t.put("definitionKey", pd.getKey());
		t.put("definitionName", pd.getName());
		return JSONResult.success(t);
	}
	
	/**
	 * 获取实例流程图
	 * @param id
	 * @param request
	 * @param response
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('workflow-instance_diagram')")
	@RequestMapping(value = "/instance/diagram", method = RequestMethod.GET)
	public void getInstanceDiagram(@RequestParam String id, HttpServletRequest request,
			HttpServletResponse response) throws Exception {
		HistoricProcessInstance d = workflowService.getHistoricProcessInstanceById(id, EsSecurityHandler.clientId());
		if(d == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		response.setCharacterEncoding(request.getCharacterEncoding());
		response.setContentType("application/octet-stream");
		response.setHeader("Content-Disposition", "attachment; filename=" + d.getProcessDefinitionKey() + "_" + id + ".png");
		StreamUtil.io(workflowService.getInstanceDiagram(id), response.getOutputStream());
		response.flushBuffer();
	}
}
