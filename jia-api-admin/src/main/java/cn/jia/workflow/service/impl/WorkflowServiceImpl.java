package cn.jia.workflow.service.impl;

import cn.jia.core.util.StringUtils;
import cn.jia.workflow.entity.DeploymentExample;
import cn.jia.workflow.entity.ProcessDefinitionExample;
import cn.jia.workflow.entity.ProcessInstanceExample;
import cn.jia.workflow.entity.TaskExample;
import cn.jia.workflow.service.WorkflowService;
import com.github.pagehelper.Page;
import org.camunda.bpm.engine.*;
import org.camunda.bpm.engine.history.*;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.repository.DeploymentQuery;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.repository.ProcessDefinitionQuery;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Comment;
import org.camunda.bpm.engine.task.IdentityLink;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.engine.task.TaskQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

@Service
public class WorkflowServiceImpl implements WorkflowService {

	@Autowired
	private RepositoryService repositoryService;
	@Autowired
	private RuntimeService runtimeService;
	@Autowired
	private TaskService taskService;
	@Autowired
	private HistoryService historyService;
	@Autowired
	private IdentityService identityService;
	
	@Override
	public void deployProcess(Deployment deployment, InputStream inputStream, String clientId) {
		repositoryService.createDeployment().addInputStream(deployment.getName()+".bpmn", inputStream).name(deployment.getName()).tenantId(clientId).deploy();
	}
	
	@Override
	public void deployProcess(Deployment deployment, ZipInputStream zipInputStream, String clientId) {
		repositoryService.createDeployment().addZipInputStream(zipInputStream).name(deployment.getName()).tenantId(clientId).deploy();
	}
	
	@Override
	public List<Deployment> getDeployment(String clientId) {
		return repositoryService.createDeploymentQuery().tenantIdIn(clientId).list();
	}
	
	@Override
	public Page<Deployment> getDeployment(DeploymentExample example, int pageNo, int pageSize, String clientId) {
		Page<Deployment> page = new Page<>(pageNo, pageSize);
		DeploymentQuery query = repositoryService.createDeploymentQuery().tenantIdIn(clientId);
		if(example != null){
			if(example.getName() != null){
				query.deploymentNameLike("%" + example.getName() + "%");
			}
		}
		query.orderByDeploymentTime().desc();
		page.setTotal(query.count());
		page.addAll(query.listPage((pageNo - 1) * pageSize, pageSize));
		return page;
	}
	
	@Override
	public Deployment getDeploymentById(String deploymentId, String clientId) {
		return repositoryService.createDeploymentQuery().tenantIdIn(clientId).deploymentId(deploymentId).singleResult();
	}
	
	@Override
	public List<String> getDeploymentResourceNames(String deploymentId) {
		return repositoryService.getDeploymentResourceNames(deploymentId);
	}
	
	@Override
	public InputStream getResourceAsStream(String deploymentId, String resourceName) {
		return repositoryService.getResourceAsStream(deploymentId, resourceName);
	}
	
	@Override
	public void deleteDeployment(String deploymentId) {
		repositoryService.deleteDeployment(deploymentId);
	}
	
	@Override
	public Page<ProcessDefinition> getProcessDefinition(ProcessDefinitionExample example, int pageNo, int pageSize, String clientId) {
		Page<ProcessDefinition> page = new Page<>(pageNo, pageSize);
		ProcessDefinitionQuery query = repositoryService.createProcessDefinitionQuery().tenantIdIn(clientId);
		if(StringUtils.isNotEmpty(example.getKey())) {
			query.processDefinitionKeyLike("%" + example.getKey() + "%");
		}
		if(StringUtils.isNotEmpty(example.getDeploymentId())) {
			query.deploymentId(example.getDeploymentId());
		}
		if(StringUtils.isNotEmpty(example.getCategory())){
			query.processDefinitionCategoryLike("%" + example.getCategory() + "%");
		}
		if(StringUtils.isNotEmpty(example.getName())){
			query.processDefinitionNameLike("%" + example.getName() + "%");
		}
		if(StringUtils.isNotEmpty(example.getResourceName())){
			query.processDefinitionResourceNameLike("%" + example.getResourceName() + "%");
		}
		query.orderByProcessDefinitionKey().asc().orderByProcessDefinitionVersion().desc();

		page.setTotal(query.count());
		page.addAll(query.listPage((pageNo - 1) * pageSize, pageSize));
		return page;
	}
	
	@Override
	public ProcessDefinition getProcessDefinitionById(String processDefinitionId) {
		return repositoryService.getProcessDefinition(processDefinitionId);
	}
	
	@Override
	public InputStream getProcessDiagram(String processDefinitionId) {
		return repositoryService.getProcessDiagram(processDefinitionId);
	}
	
	@Override
	public void activateProcessDefinition(String processDefinitionId) {
		repositoryService.activateProcessDefinitionById(processDefinitionId);
	}
	
	@Override
	public void suspendProcessDefinition(String processDefinitionId) {
		repositoryService.suspendProcessDefinitionById(processDefinitionId);
	}

	@Override
	public void startProcess(String processDefinitionKey, String businessKey, Map<String, Object> variables, String clientId) {
		identityService.setAuthenticatedUserId(String.valueOf(variables.get("applicant")));
		runtimeService.startProcessInstanceByKey(processDefinitionKey, businessKey, variables);
	}

	@Override
	public List<Task> getTasks(String assignee, String clientId) {
		return taskService.createTaskQuery().or().taskAssignee(assignee).taskCandidateUser(assignee).endOr().tenantIdIn(clientId).list();
	}
	
	@Override
	public Page<Task> getTasks(TaskExample example, int pageNo, int pageSize, String clientId) {
		Page<Task> page = new Page<>(pageNo, pageSize);
		TaskQuery query = taskService.createTaskQuery();
		if(example != null) {
			if(StringUtils.isNotEmpty(example.getAssignee())) {
				query.taskAssignee(example.getAssignee());
			}
			if(StringUtils.isNotEmpty(example.getCandidateUser())) {
				query.taskCandidateUser(example.getCandidateUser());
			}
			if(StringUtils.isNotEmpty(example.getCandidateOrAssigned())) {
				query.or().taskAssignee(example.getCandidateOrAssigned()).taskCandidateUser(example.getCandidateOrAssigned()).endOr();
			}
			if(StringUtils.isNotEmpty(example.getDefinitionKey())) {
				query.processDefinitionKey(example.getDefinitionKey());
			}
			if(StringUtils.isNotEmpty(example.getDefinitionName())) {
				query.processDefinitionNameLike("%" + example.getDefinitionName() + "%");
			}
			if(StringUtils.isNotEmpty(example.getBusinessKey())) {
				query.processInstanceBusinessKeyLike("%" + example.getBusinessKey() + "%");
			}
			if(StringUtils.isNotEmpty(example.getProcessInstanceId())) {
				query.processInstanceId(example.getProcessInstanceId());
			}
			if(StringUtils.isNotEmpty(example.getApplicant())) {
				query.processVariableValueLike("applicant", "%" + example.getApplicant() + "%");
			}
			if(example.getApplicationDateStart() != null) {
				query.processVariableValueGreaterThanOrEquals("applicationDate", example.getApplicationDateStart());
			}
			if(example.getApplicationDateEnd() != null) {
				query.processVariableValueLessThanOrEquals("applicationDate", example.getApplicationDateEnd());
			}
		}
		query.tenantIdIn(clientId).orderByTaskCreateTime().desc();
		page.setTotal(query.count());
		page.addAll(query.listPage((pageNo - 1) * pageSize, pageSize));
		return page;
	}
	
	@Override
	public Task getTaskByBusinessKey(String businessKey, String assignee, String clientId) {
		return taskService.createTaskQuery().processInstanceBusinessKey(businessKey).or().taskAssignee(assignee).taskCandidateUser(assignee).endOr().tenantIdIn(clientId).singleResult();
	}
	
	@Override
	public List<Task> getTaskByBusinessKey(String businessKey, String clientId) {
		return taskService.createTaskQuery().processInstanceBusinessKey(businessKey).tenantIdIn(clientId).list();
	}
	
	@Override
	public Task getTaskById(String taskId, String clientId) {
		return taskService.createTaskQuery().taskId(taskId).tenantIdIn(clientId).singleResult();
	}
	
	@Override
	public List<Task> getTaskByProcessInstanceId(String processInstanceId, String clientId) {
		return taskService.createTaskQuery().processInstanceId(processInstanceId).tenantIdIn(clientId).list();
	}

	@Override
	public void completeTasks(String taskId, Map<String, Object> variables) {
		taskService.complete(taskId, variables);
	}
	
	@Override
	public void deleteProcessInstance(String processInstanceId, String deleteReason) {
        runtimeService.deleteProcessInstance(processInstanceId, deleteReason);
	}
	
	@Override
	public void delegateTask(String taskId, String userId) {
		taskService.delegateTask(taskId, userId);
	}
	
	@Override
	public void claimTask(String taskId, String userId) {
		taskService.claim(taskId, userId);
	}
	
	@Override
	public void setAssignee(String taskId, String userId) {
		taskService.setAssignee(taskId, userId);
	}

	@Override
	public List<String> getCandidate(String taskId) {
		List<String> candidate = new ArrayList<>();
		List<IdentityLink> identityLinks = taskService.getIdentityLinksForTask(taskId);
		for(IdentityLink id : identityLinks){
			if("candidate".equals(id.getType())){
				candidate.add(id.getUserId());
			}
		}
		return candidate;
	}

	@Override
	public List<HistoricTaskInstance> getHistorys(String assignee, String clientId) {
		return historyService.createHistoricTaskInstanceQuery().taskAssignee(assignee).tenantIdIn(clientId).finished().list();
	}
	
	@Override
	public Page<HistoricTaskInstance> getHistorys(TaskExample example, int pageNo, int pageSize, String clientId) {
		Page<HistoricTaskInstance> page = new Page<>(pageNo, pageSize);
		HistoricTaskInstanceQuery query = historyService.createHistoricTaskInstanceQuery();
		if(example != null) {
			if(StringUtils.isNotEmpty(example.getAssignee())) {
				query = query.taskAssignee(example.getAssignee());
			}
			if(StringUtils.isNotEmpty(example.getDefinitionKey())) {
				query.processDefinitionKey(example.getDefinitionKey());
			}
			if(StringUtils.isNotEmpty(example.getDefinitionName())) {
				query.processDefinitionName(example.getDefinitionName());
			}
			if(StringUtils.isNotEmpty(example.getBusinessKey())) {
				query.processInstanceBusinessKeyLike("%" + example.getBusinessKey() + "%");
			}
			if(StringUtils.isNotEmpty(example.getProcessInstanceId())) {
				query.processInstanceId(example.getProcessInstanceId());
			}
			if(StringUtils.isNotEmpty(example.getApplicant())) {
				query.processVariableValueEquals("applicant", example.getApplicant());
			}
			/*if(example.getApplicationDateStart() != null) {
				query.processVariableValueGreaterThanOrEquals("applicationDate", example.getApplicationDateStart());
			}
			if(example.getApplicationDateEnd() != null) {
				query.processVariableValueLessThanOrEquals("applicationDate", example.getApplicationDateEnd());
			}*/
		}
		
		query = query.tenantIdIn(clientId).finished().orderByTaskDueDate().desc();
		page.setTotal(query.count());
		page.addAll(query.listPage((pageNo - 1) * pageSize, pageSize));
		return page;
	}
	
	@Override
	public Page<HistoricTaskInstance> getHistorysByBusinessKey(String businessKey, int pageNo, int pageSize, String clientId) {
		Page<HistoricTaskInstance> page = new Page<>(pageNo, pageSize);
		HistoricTaskInstanceQuery query = historyService.createHistoricTaskInstanceQuery().processInstanceBusinessKey(businessKey).tenantIdIn(clientId).finished().orderByHistoricTaskInstanceEndTime().asc();
		page.setTotal(query.count());
		page.addAll(query.listPage((pageNo - 1) * pageSize, pageSize));
		return page;
	}
	
	@Override
	public List<HistoricProcessInstance> getHistoricProcessInstances(String applicant, String clientId) {
		return historyService.createHistoricProcessInstanceQuery().startedBy(applicant).tenantIdIn(clientId).list();
	}
	
	@Override
	public Page<HistoricProcessInstance> getHistoricProcessInstances(ProcessInstanceExample example, int pageNo, int pageSize, String clientId) {
		Page<HistoricProcessInstance> page = new Page<>(pageNo, pageSize);
		HistoricProcessInstanceQuery query = historyService.createHistoricProcessInstanceQuery();
		if(example != null) {
			if(StringUtils.isNotEmpty(example.getApplicant())) {
				query.startedBy(example.getApplicant());
			}
			if(StringUtils.isNotEmpty(example.getDefinitionKey())) {
				query.processDefinitionKey(example.getDefinitionKey());
			}
			if(StringUtils.isNotEmpty(example.getDefinitionName())) {
				query.processDefinitionName(example.getDefinitionName());
			}
			if(example.getStartedBefore() != null) {
				query.startedBefore(example.getStartedBefore());
			}
			if(example.getStartedAfter() != null) {
				query.startedAfter(example.getStartedAfter());
			}
			if(example.getFinishedBefore() != null) {
				query.finishedBefore(example.getFinishedBefore());
			}
			if(example.getFinishedAfter() != null) {
				query.finishedAfter(example.getFinishedAfter());
			}
			if(StringUtils.isNotEmpty(example.getBusinessKey())) {
				query.processInstanceBusinessKey(example.getBusinessKey());
			}
		}
		
		query.tenantIdIn(clientId).orderByProcessInstanceStartTime().desc();
		page.setTotal(query.count());
		page.addAll(query.listPage((pageNo - 1) * pageSize, pageSize));
		return page;
	}
	
	@Override
    public ProcessInstance getProcessInstanceByTask(Task task, String clientId) {
        //得到当前任务的流程
		return runtimeService.createProcessInstanceQuery().tenantIdIn(clientId)
				.processInstanceId(task.getProcessInstanceId()).singleResult();
    }
	
	@Override
    public HistoricProcessInstance getHistoricProcessInstanceById(String instanceId, String clientId) {
        //得到当前任务的流程
		return historyService.createHistoricProcessInstanceQuery().tenantIdIn(clientId)
                .processInstanceId(instanceId).singleResult();
    }
	
	@Override
    @SuppressWarnings("unchecked")
	public <T> T getProcessVariables(String taskId, String variableName, Class<T> variableClass) {
		return (T)taskService.getVariable(taskId, variableName);
	}
	
	@Override
	public Object getProcessVariables(String taskId, String variableName) {
		return taskService.getVariable(taskId, variableName);
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public <T> T getHistoricVariable(String processInstanceId, String variableName, Class<T> variableClass) {
		HistoricVariableInstance instance = historyService.createHistoricVariableInstanceQuery().processInstanceId(processInstanceId).variableName(variableName).singleResult();
		if(instance != null && instance.getValue() != null) {
			return (T) instance.getValue();
		}else {
			return null;
		}
	}
	
	@Override
	public Object getHistoricVariable(String processInstanceId, String variableName) {
		HistoricVariableInstance instance = historyService.createHistoricVariableInstanceQuery().processInstanceId(processInstanceId).variableName(variableName).singleResult();
		if(instance != null && instance.getValue() != null) {
			return instance.getValue();
		}else {
			return null;
		}
	}
	
	@Override
	public List<HistoricVariableInstance> getHistoricVariables(String processInstanceId, String taskId) {
		return historyService.createHistoricVariableInstanceQuery().processInstanceId(processInstanceId).taskIdIn(taskId).list();
	}
	
	@Override
	public List<HistoricVariableInstance> getHistoricVariables(String processInstanceId) {
		return historyService.createHistoricVariableInstanceQuery().processInstanceId(processInstanceId).list();
	}
	
	@Override
	public Object getTaskVariable(String taskId, String variableName) {
		return taskService.getVariable(taskId, variableName);
	}
	
	@Override
	public Map<String, Object> getTaskVariables(String taskId) {
		return taskService.getVariables(taskId);
	}
	
	@Override
	public void setProcessVariables(String taskId, Map<String, Object> variables) {
		taskService.setVariables(taskId, variables);
	}
	
	@Override
	public void setProcessVariable(String taskId, String variableName, Object value) {
		taskService.setVariable(taskId, variableName, value);
	}
	
	@Override
	public void setTaskVariables(String taskId, Map<String, Object> variables) {
		taskService.setVariablesLocal(taskId, variables);
	}
	
	@Override
	public void setTaskVariable(String taskId, String variableName, Object value) {
		taskService.setVariableLocal(taskId, variableName, value);
	}
	
	@Override
	public List<Comment> getTaskComments(String taskId) {
		return taskService.getTaskComments(taskId);
	}
	
	@Override
	public String getTaskComment(String taskId) {
		List<String> comment = new ArrayList<>();
		List<Comment> commentList = taskService.getTaskComments(taskId);
		for(Comment c : commentList) {
			comment.add(c.getFullMessage());
		}
		return String.join(",", comment);
	}
	
	@Override
	public void addComment(String taskId, String message) {
		Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
//		Authentication.setAuthenticatedUserId(task.getAssignee());
		taskService.createComment(taskId, task.getProcessInstanceId(), message);
	}
	
	@Override
	public InputStream getInstanceDiagram(String instanceId) {
        return repositoryService.getProcessDiagram(instanceId);
    }

}
