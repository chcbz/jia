package cn.jia.workflow.service.impl;

import cn.jia.core.common.EsSecurityHandler;
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
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.*;
import org.camunda.bpm.model.bpmn.instance.bpmndi.*;
import org.camunda.bpm.model.bpmn.instance.dc.Bounds;
import org.camunda.bpm.model.bpmn.instance.di.Waypoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
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
	public void deployProcess(Deployment deployment, InputStream inputStream) {
		String clientId = EsSecurityHandler.clientId();
		repositoryService.createDeployment().addInputStream(deployment.getName()+".bpmn", inputStream).name(deployment.getName()).tenantId(clientId).deploy();
	}
	
	@Override
	public void deployProcess(Deployment deployment, ZipInputStream zipInputStream) {
		String clientId = EsSecurityHandler.clientId();
		repositoryService.createDeployment().addZipInputStream(zipInputStream).name(deployment.getName()).tenantId(clientId).deploy();
	}
	
	@Override
	public List<Deployment> getDeployment() {
		return repositoryService.createDeploymentQuery().tenantIdIn(EsSecurityHandler.clientId()).list();
	}
	
	@Override
	public Page<Deployment> getDeployment(DeploymentExample example, int pageNo, int pageSize) {
		Page<Deployment> page = new Page<>(pageNo, pageSize);
		DeploymentQuery query = repositoryService.createDeploymentQuery().tenantIdIn(EsSecurityHandler.clientId());
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
	public Deployment getDeploymentById(String deploymentId) {
		return repositoryService.createDeploymentQuery().tenantIdIn(EsSecurityHandler.clientId()).deploymentId(deploymentId).singleResult();
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
	public Page<ProcessDefinition> getProcessDefinition(ProcessDefinitionExample example, int pageNo, int pageSize) {
		Page<ProcessDefinition> page = new Page<>(pageNo, pageSize);
		ProcessDefinitionQuery query = repositoryService.createProcessDefinitionQuery().tenantIdIn(EsSecurityHandler.clientId());
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
		query.orderByDeploymentId().desc();

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
	public void startProcess(String processDefinitionKey, String businessKey, Map<String, Object> variables) {
		identityService.setAuthentication(String.valueOf(variables.get("applicant")), null, Collections.singletonList(EsSecurityHandler.clientId()));
		runtimeService.startProcessInstanceByKey(processDefinitionKey, businessKey, variables);
	}

	@Override
	public List<Task> getTasks(String assignee) {
		return taskService.createTaskQuery().or().taskAssignee(assignee).taskCandidateUser(assignee).endOr().tenantIdIn(EsSecurityHandler.clientId()).list();
	}
	
	@Override
	public Page<Task> getTasks(TaskExample example, int pageNo, int pageSize) {
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
		}
		query.tenantIdIn(EsSecurityHandler.clientId()).orderByTaskCreateTime().desc();
		page.setTotal(query.count());
		page.addAll(query.listPage((pageNo - 1) * pageSize, pageSize));
		return page;
	}
	
	@Override
	public Task getTaskByBusinessKey(String businessKey, String assignee) {
		return taskService.createTaskQuery().processInstanceBusinessKey(businessKey).or().taskAssignee(assignee).taskCandidateUser(assignee).endOr().tenantIdIn(EsSecurityHandler.clientId()).singleResult();
	}
	
	@Override
	public List<Task> getTaskByBusinessKey(String businessKey) {
		return taskService.createTaskQuery().processInstanceBusinessKey(businessKey).tenantIdIn(EsSecurityHandler.clientId()).list();
	}
	
	@Override
	public Task getTaskById(String taskId) {
		return taskService.createTaskQuery().taskId(taskId).tenantIdIn(EsSecurityHandler.clientId()).singleResult();
	}
	
	@Override
	public List<Task> getTaskByProcessInstanceId(String processInstanceId) {
		return taskService.createTaskQuery().processInstanceId(processInstanceId).tenantIdIn(EsSecurityHandler.clientId()).list();
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
	public List<HistoricTaskInstance> getHistorys(String assignee) {
		return historyService.createHistoricTaskInstanceQuery().taskAssignee(assignee).tenantIdIn(EsSecurityHandler.clientId()).finished().list();
	}
	
	@Override
	public Page<HistoricTaskInstance> getHistorys(TaskExample example, int pageNo, int pageSize) {
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
		}
		
		query = query.tenantIdIn(EsSecurityHandler.clientId()).finished().orderByTaskDueDate().desc();
		page.setTotal(query.count());
		page.addAll(query.listPage((pageNo - 1) * pageSize, pageSize));
		return page;
	}
	
	@Override
	public Page<HistoricTaskInstance> getHistorysByBusinessKey(String businessKey, int pageNo, int pageSize) {
		Page<HistoricTaskInstance> page = new Page<>(pageNo, pageSize);
		HistoricTaskInstanceQuery query = historyService.createHistoricTaskInstanceQuery().processInstanceBusinessKey(businessKey).tenantIdIn(EsSecurityHandler.clientId()).finished().orderByHistoricTaskInstanceEndTime().asc();
		page.setTotal(query.count());
		page.addAll(query.listPage((pageNo - 1) * pageSize, pageSize));
		return page;
	}
	
	@Override
	public List<HistoricProcessInstance> getHistoricProcessInstances(String applicant) {
		return historyService.createHistoricProcessInstanceQuery().startedBy(applicant).tenantIdIn(EsSecurityHandler.clientId()).list();
	}
	
	@Override
	public Page<HistoricProcessInstance> getHistoricProcessInstances(ProcessInstanceExample example, int pageNo, int pageSize) {
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
		
		query.tenantIdIn(EsSecurityHandler.clientId()).orderByProcessInstanceStartTime().desc();
		page.setTotal(query.count());
		page.addAll(query.listPage((pageNo - 1) * pageSize, pageSize));
		return page;
	}
	
	@Override
    public ProcessInstance getProcessInstanceByTask(Task task) {
        //得到当前任务的流程
		return runtimeService.createProcessInstanceQuery().tenantIdIn(EsSecurityHandler.clientId())
				.processInstanceId(task.getProcessInstanceId()).singleResult();
    }
	
	@Override
    public HistoricProcessInstance getHistoricProcessInstanceById(String instanceId) {
        //得到当前任务的流程
		return historyService.createHistoricProcessInstanceQuery().tenantIdIn(EsSecurityHandler.clientId())
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
		taskService.createComment(taskId, task.getProcessInstanceId(), message);
	}
	
	@Override
	public InputStream getInstanceDiagram(String instanceId) {
		return null;
//        try {
//            // 获取历史流程实例
//            HistoricProcessInstance historicProcessInstance = historyService.createHistoricProcessInstanceQuery().processInstanceId(instanceId).singleResult();
//
//            // 获取流程中已经执行的节点，按照执行先后顺序排序
//            List<HistoricActivityInstance> historicActivityInstanceList = historyService.createHistoricActivityInstanceQuery().processInstanceId(instanceId).orderByHistoricActivityInstanceId().asc().list();
//
//            // 构造已执行的节点ID集合
//            List<String> executedActivityIdList = new ArrayList<>();
//            for (HistoricActivityInstance activityInstance : historicActivityInstanceList) {
//                executedActivityIdList.add(activityInstance.getActivityId());
//            }
//
//            // 获取bpmnModel
//            BpmnModel bpmnModel = repositoryService.getBpmnModel(historicProcessInstance.getProcessDefinitionId());
//            // 获取流程已发生流转的线ID集合
//            List<String> flowIds = this.getExecutedFlows(bpmnModel, historicActivityInstanceList);
//
//            // 使用默认配置获得流程图表生成器，并生成追踪图片字符流
//            ProcessDiagramGenerator processDiagramGenerator = new DefaultProcessDiagramGenerator();
//			return processDiagramGenerator.generateDiagram(bpmnModel, "png", executedActivityIdList, flowIds, "宋体", "微软雅黑", "黑体", null, 2.0);
//        } catch (Exception e) {
//            e.printStackTrace();
//            return null;
//        }
    }
	
//	private List<String> getExecutedFlows(BpmnModel bpmnModel, List<HistoricActivityInstance> historicActivityInstances) {
//        // 流转线ID集合
//        List<String> flowIdList = new ArrayList<>();
//        // 全部活动实例
//        List<FlowNode> historicFlowNodeList = new LinkedList<>();
//        // 已完成的历史活动节点
//        List<HistoricActivityInstance> finishedActivityInstanceList = new LinkedList<>();
//        for (HistoricActivityInstance historicActivityInstance : historicActivityInstances) {
//            historicFlowNodeList.add((FlowNode) bpmnModel.getMainProcess().getFlowElement(historicActivityInstance.getActivityId(), true));
//            if (historicActivityInstance.getEndTime() != null) {
//                finishedActivityInstanceList.add(historicActivityInstance);
//            }
//        }
//
//        // 遍历已完成的活动实例，从每个实例的outgoingFlows中找到已执行的
//        FlowNode currentFlowNode;
//        for (HistoricActivityInstance currentActivityInstance : finishedActivityInstanceList) {
//            // 获得当前活动对应的节点信息及outgoingFlows信息
//            currentFlowNode = (FlowNode) bpmnModel.getMainProcess().getFlowElement(currentActivityInstance.getActivityId(), true);
//            List<SequenceFlow> sequenceFlowList = currentFlowNode.getOutgoingFlows();
//
//            /*
//              遍历outgoingFlows并找到已已流转的
//              满足如下条件认为已已流转：
//              1.当前节点是并行网关或包含网关，则通过outgoingFlows能够在历史活动中找到的全部节点均为已流转
//              2.当前节点是以上两种类型之外的，通过outgoingFlows查找到的时间最近的流转节点视为有效流转
//             */
//            FlowNode targetFlowNode;
//            if (BpmsActivityTypeEnum.PARALLEL_GATEWAY.getType().equals(currentActivityInstance.getActivityType())
//                    || BpmsActivityTypeEnum.INCLUSIVE_GATEWAY.getType().equals(currentActivityInstance.getActivityType())) {
//                // 遍历历史活动节点，找到匹配Flow目标节点的
//                for (SequenceFlow sequenceFlow : sequenceFlowList) {
//                    targetFlowNode = (FlowNode) bpmnModel.getMainProcess().getFlowElement(sequenceFlow.getTargetRef(), true);
//                    if (historicFlowNodeList.contains(targetFlowNode)) {
//                        flowIdList.add(sequenceFlow.getId());
//                    }
//                }
//            } else {
//                List<Map<String, String>> tempMapList = new LinkedList<>();
//                // 遍历历史活动节点，找到匹配Flow目标节点的
//                for (SequenceFlow sequenceFlow : sequenceFlowList) {
//                    for (HistoricActivityInstance historicActivityInstance : historicActivityInstances) {
//                        if (historicActivityInstance.getActivityId().equals(sequenceFlow.getTargetRef())) {
//                            tempMapList.add(DataUtil.toMap("flowId", sequenceFlow.getId(), "activityStartTime", String.valueOf(historicActivityInstance.getStartTime().getTime())));
//                        }
//                    }
//                }
//
//                // 遍历匹配的集合，取得开始时间最早的一个
//                long earliestStamp = 0L;
//                String flowId = null;
//                for (Map<String, String> map : tempMapList) {
//                    long activityStartTime = Long.valueOf(map.get("activityStartTime"));
//                    if (earliestStamp == 0 || earliestStamp >= activityStartTime) {
//                        earliestStamp = activityStartTime;
//                        flowId = map.get("flowId");
//                    }
//                }
//                flowIdList.add(flowId);
//            }
//        }
//        return flowIdList;
//    }


	public void generateProcess() throws IOException {
		BpmnModelInstance modelInstance = Bpmn.createEmptyModel();
		Definitions definitions = modelInstance.newInstance(Definitions.class);
		definitions.setTargetNamespace("http://camunda.org/examples");
		modelInstance.setDefinitions(definitions);

		// create the process
		Process process = modelInstance.newInstance(Process.class);
		process.setAttributeValue("id", "process-one-task", true);
		definitions.addChildElement(process);

		BpmnDiagram diagram = modelInstance.newInstance(BpmnDiagram.class);
		BpmnPlane plane = modelInstance.newInstance(BpmnPlane.class);
		plane.setBpmnElement(process);
		diagram.setBpmnPlane(plane);
		definitions.addChildElement(diagram);

		// create start event, user task and end event
		StartEvent startEvent = createElement(modelInstance, process, "start", "Di generation wanted",
				StartEvent.class, plane, 15, 15, 50, 50, true);

		UserTask userTask = createElement(modelInstance, process, "userTask", "Generate Model with DI",
				UserTask.class, plane, 100, 0, 80, 100, false);

		createSequenceFlow(modelInstance, process, startEvent, userTask, plane, 65, 40, 100, 40);

		EndEvent endEvent = createElement(modelInstance, process, "end", "DI generation completed",
				EndEvent.class, plane, 250, 15, 50, 50, true);

		createSequenceFlow(modelInstance, process, userTask, endEvent, plane, 200, 40, 250, 40);

		// validate and write model to file
		Bpmn.validateModel(modelInstance);
		File file = File.createTempFile("bpmn-model-api-", ".bpmn");
		Bpmn.writeModelToFile(file, modelInstance);

	}

	private <T extends BpmnModelElementInstance> T createElement(BpmnModelInstance modelInstance, BpmnModelElementInstance parentElement,
																 String id, String name, Class<T> elementClass, BpmnPlane plane,
																 double x, double y, double heigth, double width, boolean withLabel) {
		T element = modelInstance.newInstance(elementClass);
		element.setAttributeValue("id", id, true);
		element.setAttributeValue("name", name, false);
		parentElement.addChildElement(element);

		BpmnShape bpmnShape = modelInstance.newInstance(BpmnShape.class);
		bpmnShape.setBpmnElement((BaseElement) element);

		Bounds bounds = modelInstance.newInstance(Bounds.class);
		bounds.setX(x);
		bounds.setY(y);
		bounds.setHeight(heigth);
		bounds.setWidth(width);
		bpmnShape.setBounds(bounds);

		if (withLabel) {
			BpmnLabel bpmnLabel = modelInstance.newInstance(BpmnLabel.class);
			Bounds labelBounds = modelInstance.newInstance(Bounds.class);
			labelBounds.setX(x);
			labelBounds.setY(y + heigth);
			labelBounds.setHeight(heigth);
			labelBounds.setWidth(width);
			bpmnLabel.addChildElement(labelBounds);
			bpmnShape.addChildElement(bpmnLabel);
		}
		plane.addChildElement(bpmnShape);

		return element;
	}

	private SequenceFlow createSequenceFlow(BpmnModelInstance modelInstance, Process process, FlowNode from, FlowNode to, BpmnPlane plane,
											int... waypoints) {
		String identifier = from.getId() + "-" + to.getId();
		SequenceFlow sequenceFlow = modelInstance.newInstance(SequenceFlow.class);
		sequenceFlow.setAttributeValue("id", identifier, true);
		process.addChildElement(sequenceFlow);
		sequenceFlow.setSource(from);
		from.getOutgoing().add(sequenceFlow);
		sequenceFlow.setTarget(to);
		to.getIncoming().add(sequenceFlow);

		BpmnEdge bpmnEdge = modelInstance.newInstance(BpmnEdge.class);
		bpmnEdge.setBpmnElement(sequenceFlow);
		for (int i = 0; i < waypoints.length / 2; i++) {
			double waypointX = waypoints[i*2];
			double waypointY = waypoints[i*2+1];
			Waypoint wp = modelInstance.newInstance(Waypoint.class);
			wp.setX(waypointX);
			wp.setY(waypointY);
			bpmnEdge.addChildElement(wp);
		}
		plane.addChildElement(bpmnEdge);

		return sequenceFlow;
	}

}
