package cn.jia.task.entity;

import java.math.BigDecimal;

import lombok.Data;

@Data
public class TaskItemVOExample {
	private Integer id;

    private Integer planId;

    private String jiacn;

    private Integer type;

    private Integer period;

    private String crond;

    private String name;

    private String description;

    private BigDecimal amount;

    private Integer remind;

    private Integer status;

    private Long timeStart;
    
    private Long timeEnd;
}