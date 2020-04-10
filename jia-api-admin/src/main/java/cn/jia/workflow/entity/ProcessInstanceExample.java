package cn.jia.workflow.entity;

import java.util.Date;

import lombok.Data;

@Data
public class ProcessInstanceExample {
	
	private String applicant;
	
	private String definitionName;
	
	private String definitionKey;
	
	private Date startedBefore;
	
	private Date startedAfter;
	
	private Date finishedBefore;
	
	private Date finishedAfter;
	
	private String businessKey;
}
