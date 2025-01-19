package cn.jia.workflow.entity;

import lombok.Data;

@Data
public class ProcessDefinitionExample {
    private String name;
    private String description;
    private String key;
    private int version;
    private String category;
    private String deploymentId;
    private String resourceName;
}
