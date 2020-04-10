package cn.jia.core.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import org.activiti.engine.ProcessEngine;
import org.activiti.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.activiti.engine.repository.Deployment;
import org.activiti.engine.repository.ProcessDefinition;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.Task;

/**
 * @author scw
 * @create 2018-01-24 9:51
 * @desc 针对流程管理的工具类
 **/
public class ActivitiUtils {
	
    private ProcessEngine processEngine;
 
    /**
     * 部署流程
     * @param file 流程的zip文件
     * @param processName  流程的名字
     * @throws IOException
     */
    public void deployeProcess(File file , String processName)throws IOException{
        InputStream inputStream = new FileInputStream(file);
        ZipInputStream zipInputStream = new ZipInputStream(inputStream);
        this.processEngine.getRepositoryService()
                .createDeployment()
                .name(processName)
                .addZipInputStream(zipInputStream)
                .deploy();
    }
 
    /**
     * 通过字节流来进行部署流程
     * @param io
     * @param processName
     */
    public  void deplyoProcessByInputSterm(InputStream io , String processName){
        ZipInputStream zipInputStream = new ZipInputStream(io);
        this.processEngine.getRepositoryService()
                .createDeployment()
                .name(processName)
                .addZipInputStream(zipInputStream)
                .deploy();
    }
 
 
    /**
     * 查询所有的部署流程
     * @return
     */
    public List<Deployment> getAllDeplyoment(){
        return this.processEngine.getRepositoryService()
                .createDeploymentQuery()
                .orderByDeploymenTime()
                .desc()
                .list();
    }
    /**
     * 查询所有的部署定义信息
     * @return
     */
    public List<ProcessDefinition> getAllProcessInstance(){
        return this.processEngine.getRepositoryService()
                .createProcessDefinitionQuery()
                .orderByProcessDefinitionVersion()
                .desc()
                .list();
    }
 
    /**
     * 根据部署ID，来删除部署
     * @param deplyomenId
     */
    public void deleteDeplyomentByPID(String deplyomenId){
        this.processEngine.getRepositoryService()
                .deleteDeployment(deplyomenId , true);
    }
 
    /**
     * 查询某个部署流程的流程图
     * @param pid
     * @return
     */
    public InputStream lookProcessPicture(String pid){
        return this.processEngine.getRepositoryService()
                .getProcessDiagram(pid);
    }
 
    /**
     * 开启请假的流程实例
     * @param billId
     * @param userId
     */
    public void startProceesInstance(Long billId , String userId){
        Map<String , Object> variables = new HashMap<>();
        variables.put("userID" , userId);
        this.processEngine.getRuntimeService()
                .startProcessInstanceByKey("shenqingtest" , ""+billId , variables); //第一个参数，就是流程中自己定义的名字，这个一定要匹配，否则是找不到的。
    }
 
    /**
     * 查询当前登陆人的所有任务
     * @param userId
     * @return
     */
    public List<Task> queryCurretUserTaskByAssignerr(String userId){
        return this.processEngine.getTaskService()
                .createTaskQuery()
                .taskAssignee(userId)
                .orderByTaskCreateTime()
                .desc()
                .list();
    }
 
    /**
     * 根据taskId，判断对应的流程实例是否结束
     * 如果结束了，那么得到的流程实例就是返回一个null
     * 否则就是返回对应的流程实例对象
     * 当然也可以选择返回boolean类型的
     * @param taskId  任务ID
     * @return
     */
    public ProcessInstance isFinishProcessInstancs(String taskId){
        //1,先根据taskid，得到任务
        Task task = getTaskByTaskId(taskId);
        //2:完成当前任务
        finishCurrentTaskByTaskId(taskId);
        //3:得到当前任务对应得的流程实例对象
        ProcessInstance processInstance = getProcessInstanceByTask(task);
        return processInstance;
    }
 
    /**
     * 根据taskId获取到task
     * @param taskId
     * @return
     */
    public Task getTaskByTaskId(String taskId) {
        //得到当前的任务
        Task task = this.processEngine.getTaskService()
                .createTaskQuery()
                .taskId(taskId)
                .singleResult();
        return task;
    }
 
    /**
     * 根据Task中的流程实例的ID，来获取对应的流程实例
     * @param task 流程中的任务
     * @return
     */
    public ProcessInstance getProcessInstanceByTask(Task task) {
        //得到当前任务的流程
        ProcessInstance processInstance = this.processEngine.getRuntimeService()
                .createProcessInstanceQuery()
                .processInstanceId(task.getProcessInstanceId())
                .singleResult();
        return processInstance;
    }
 
    /**
     * 根据Task来获取对应的流程定义信息
     * @param task
     * @return
     */
    public ProcessDefinitionEntity getProcessDefinitionEntityByTask(Task task){
        ProcessDefinitionEntity processDefinitionEntity = (ProcessDefinitionEntity) this.processEngine.getRepositoryService()
                .getProcessDefinition(task.getProcessDefinitionId());
        return processDefinitionEntity;
    }
 
    /**
     * 根据taskId获取到businesskey，这个值是管理activiti表和自己流程业务表的关键之处
     * @param taskId 任务的ID
     * @return
     */
    public String getBusinessKeyByTaskId(String taskId){
        Task task = this.getTaskByTaskId(taskId);
        ProcessInstance processInstance = this.getProcessInstanceByTask(task);
        //返回值
        return processInstance.getBusinessKey();
    }
 
    /**
     * 根据taskId，完成任务
     * @param taskId
     */
    public void finishCurrentTaskByTaskId(String taskId){
        this.processEngine.getTaskService().complete(taskId);
    }
 
    /**
     * 完成任务的同时，进行下一个节点的审批人员的信息的传递
     * @param taskId
     * @param object
     */
    public void finishCurrentTaskByTaskId(String taskId , Object object){
        Map<String , Object> map = new HashMap<>();
        map.put("assigeUser" , object);
        this.processEngine.getTaskService().complete(taskId , map);
    }
}