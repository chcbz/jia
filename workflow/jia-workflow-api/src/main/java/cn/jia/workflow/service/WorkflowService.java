package cn.jia.workflow.service;

import cn.jia.workflow.entity.DeploymentExample;
import cn.jia.workflow.entity.ProcessDefinitionExample;
import cn.jia.workflow.entity.ProcessInstanceExample;
import cn.jia.workflow.entity.TaskExample;
import com.github.pagehelper.Page;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.history.HistoricTaskInstance;
import org.camunda.bpm.engine.history.HistoricVariableInstance;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Comment;
import org.camunda.bpm.engine.task.Task;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

public interface WorkflowService {

	/**
	 * 部署工作流
	 * @param deployment
	 * @param inputStream
	 */
	void deployProcess(Deployment deployment, InputStream inputStream);
	
	void deployProcess(Deployment deployment, ZipInputStream zipInputStream);
	
	/**
	 * 获取工作流列表
	 * @return
	 */
	List<Deployment> getDeployment();
	
	/**
	 * 分页获取工作流列表
	 * @param pageNo
	 * @param pageSize
	 * @return
	 */
	Page<Deployment> getDeployment(DeploymentExample example, int pageNo, int pageSize);
	
	/**
	 * 根据ID获取工作流
	 * @param deploymentId
	 * @return
	 */
	Deployment getDeploymentById(String deploymentId);
	
	/**
	 * 获取工作流部署资源列表
	 * @param deploymentId
	 * @return
	 */
	List<String> getDeploymentResourceNames(String deploymentId);
	
	/**
	 * 删除工作流
	 * @param deploymentId
	 */
	void deleteDeployment(String deploymentId);
	
	/**
	 * 获取工作流定义列表
	 * @param example
	 * @param pageNo
	 * @param pageSize
	 * @return
	 */
	Page<ProcessDefinition> getProcessDefinition(ProcessDefinitionExample example, int pageNo, int pageSize);
	
	/**
	 * 获取工作流定义信息
	 * @param processDefinitionId
	 * @return
	 */
	ProcessDefinition getProcessDefinitionById(String processDefinitionId);
	
	/**
	 * 获取工作流图解内容
	 * @param processDefinitionId
	 * @return
	 */
	InputStream getProcessDiagram(String processDefinitionId);
	
	/**
	 * 获取工作流定义内容
	 * @param deploymentId
	 * @param resourceName
	 * @return
	 */
	InputStream getResourceAsStream(String deploymentId, String resourceName);
	
	/**
	 * 激活工作流
	 * @param processDefinitionId
	 */
	void activateProcessDefinition(String processDefinitionId);
	
	/**
	 * 挂起工作流
	 * @param processDefinitionId
	 */
	void suspendProcessDefinition(String processDefinitionId);

	/**
	 * 开始任务
	 * @param processDefinitionKey
	 * @param businessKey
	 * @param variables
	 */
	void startProcess(String processDefinitionKey, String businessKey, Map<String, Object> variables);

	/**
	 * 获得某个人的任务别表
	 * @param assignee
	 * @return
	 */
	List<Task> getTasks(String assignee);
	
	/**
	 * 分页显示某个人的任务列表
	 * @param example
	 * @param pageNo
	 * @param pageSize
	 * @return
	 */
	Page<Task> getTasks(TaskExample example, int pageNo, int pageSize);
	
	/**
	 * 根据业务编号查找当前用户的最新任务
	 * @param businessKey
	 * @return
	 */
	Task getTaskByBusinessKey(String businessKey, String assignee);
	
	/**
	 * 根据业务编号查找最新任务列表
	 * @param businessKey
	 * @return
	 */
	List<Task> getTaskByBusinessKey(String businessKey);
	
	/**
	 * 根据任务ID获取任务信息
	 * @param taskId
	 * @return
	 */
	Task getTaskById(String taskId);
	
	/**
	 * 根据实例ID获取
	 * @param processInstanceId
	 * @return
	 */
	List<Task> getTaskByProcessInstanceId(String processInstanceId);

	/**
	 * 完成任务
	 * @param taskId
	 * @param variables
	 */
	void completeTasks(String taskId, Map<String, Object> variables);
	
	/**
	 * 删除流程实例
	 * @param processInstanceId
	 * @param deleteReason
	 */
	void deleteProcessInstance(String processInstanceId, String deleteReason);
	
	/**
	 * 委托受理人
	 * @param taskId 任务ID
	 * @param userId 被委托人
	 */
	void delegateTask(String taskId, String userId);
	
	/**
	 * 任务认领
	 * @param taskId 任务ID
	 * @param userId 认领人ID
	 */
	void claimTask(String taskId, String userId);
	
	/**
	 * 设置受理人
	 * @param taskId 任务ID
	 * @param userId 受理人ID
	 */
	void setAssignee(String taskId, String userId);

	/**
	 * 获取任务审批人列表
	 * @param taskId
	 * @return
	 */
	List<String> getCandidate(String taskId);
	
	/**
	 * 获取历史审批列表
	 * @param assignee
	 * @return
	 */
	List<HistoricTaskInstance> getHistorys(String assignee);
	
	/**
	 * 分页显示历史审批列表
	 * @param example
	 * @param pageNo
	 * @param pageSize
	 * @return
	 */
	Page<HistoricTaskInstance> getHistorys(TaskExample example, int pageNo, int pageSize);
	
	/**
	 * 根据业务编码分页显示历史审批列表
	 * @param businessKey
	 * @param pageNo
	 * @param pageSize
	 * @return
	 */
	Page<HistoricTaskInstance> getHistorysByBusinessKey(String businessKey, int pageNo, int pageSize);
	
	/**
	 * 获取历史实例列表
	 * @param applicant
	 * @return
	 */
	List<HistoricProcessInstance> getHistoricProcessInstances(String applicant);
	
	/**
	 * 分页显示历史实例列表
	 * @param example
	 * @param pageNo
	 * @param pageSize
	 * @return
	 */
	Page<HistoricProcessInstance> getHistoricProcessInstances(ProcessInstanceExample example, int pageNo, int pageSize);
	
	/**
     * 根据Task中的流程实例的ID，来获取对应的流程实例
     * @param task 流程中的任务
     * @return
     */
	ProcessInstance getProcessInstanceByTask(Task task);
    
    /**
     * 根据历史Task中的流程实例的ID，来获取对应的历史流程实例
     * @param instanceId
     * @return
     */
	HistoricProcessInstance getHistoricProcessInstanceById(String instanceId);
    
    /**
     * 根据taskId获取变量值
     * @param taskId
     * @param variableName
     * @param variableClass
     * @return
     */
	<T> T getProcessVariables(String taskId, String variableName, Class<T> variableClass);
    
    /**
     * 根据taskId获取变量值
     * @param taskId
     * @param variableName
     * @return
     */
	Object getProcessVariables(String taskId, String variableName);
    
    /**
     * 根据实例ID获取历史变量值
     * @param processInstanceId
     * @param variableName
     * @param variableClass
     * @return
     */
	<T> T getHistoricVariable(String processInstanceId, String variableName, Class<T> variableClass);
    
    /**
     * 根据实例ID获取历史变量值
     * @param processInstanceId
     * @param variableName
     * @return
     */
	Object getHistoricVariable(String processInstanceId, String variableName);
    
    /**
     * 获取任务变量列表
     * @param processInstanceId
     * @param taskId
     * @return
     */
	List<HistoricVariableInstance> getHistoricVariables(String processInstanceId, String taskId);
	
	/**
	 * 获取实例变量列表
	 * @param processInstanceId
	 * @return
	 */
	List<HistoricVariableInstance> getHistoricVariables(String processInstanceId);
	
	/**
	 * 获取任务的指定变量
	 * @param taskId
	 * @param variableName
	 * @return
	 */
	Object getTaskVariable(String taskId, String variableName);
	
	/**
	 * 获取任务所有变量
	 * @param taskId
	 * @return
	 */
	Map<String, Object> getTaskVariables(String taskId);
	
	/**
	 * 设置流程变量
	 * @param taskId
	 * @param variables
	 */
	void setProcessVariables(String taskId, Map<String, Object> variables);
	
	/**
	 * 设置流程变量
	 * @param taskId
	 * @param variableName
	 * @param value
	 */
	void setProcessVariable(String taskId, String variableName, Object value);
	
	/**
	 * 设置任务变量
	 * @param taskId
	 * @param variables
	 */
	void setTaskVariables(String taskId, Map<String, Object> variables);
	
	/**
	 * 设置任务变量
	 * @param taskId
	 * @param variableName
	 * @param value
	 */
	void setTaskVariable(String taskId, String variableName, Object value);
	
	/**
	 * 获取任务审批批注列表
	 * @param taskId
	 * @return
	 */
	List<Comment> getTaskComments(String taskId);
	
	/**
	 * 获取任务审批批注信息
	 * @param taskId
	 * @return
	 */
	String getTaskComment(String taskId);
	
	/**
	 * 添加任务批注
	 * @param taskId
	 * @param message
	 */
	void addComment(String taskId, String message);
	
	/**
	 * 获取实例流程图
	 * @param instanceId
	 * @return
	 */
	InputStream getInstanceDiagram(String instanceId);
}
