package cn.jia.task.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
public class TaskItemVOExample extends TaskItemVO {
    private Long timeStart;
    
    private Long timeEnd;
}