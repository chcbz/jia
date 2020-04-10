package cn.jia.workflow.entity;

import lombok.Data;

@Data
public class TaskExample {
	private String name;
	private String taskDefinitionKey;
	private String assignee;
	private String candidateUser;
	private String candidateOrAssigned;
	private String processInstanceId;
	
	private String applicant;
	private String applicationDateStart;
	private String applicationDateEnd;
	private String businessKey;
	private String definitionKey;
	private String definitionName;
}
