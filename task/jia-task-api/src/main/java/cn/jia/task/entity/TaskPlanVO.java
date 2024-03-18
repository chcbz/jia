package cn.jia.task.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(callSuper = true)
public class TaskPlanVO extends TaskPlanEntity {

    private Long startTimeStart;

    private Long startTimeEnd;

    private Long endTimeStart;

    private Long endTimeEnd;

    private Integer historyFlag;
}
