package cn.jia.user.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
public class UserExample extends User {
    private Long createTimeStart;
    private Long createTimeEnd;
    private Long updateTimeStart;
    private Long updateTimeEnd;
    private Integer orgId;
    private Integer roleId;
    private Integer groupId;
    private List<String> jiacnList;
}
