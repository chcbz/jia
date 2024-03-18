package cn.jia.task.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(callSuper = true)
public class TaskDetailVO extends TaskDetailEntity {
    private Long timeStart;

    private Long timeEnd;
}
