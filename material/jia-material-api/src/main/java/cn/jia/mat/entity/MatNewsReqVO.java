package cn.jia.mat.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(callSuper = true)
public class MatNewsReqVO extends MatNewsEntity {
    private Integer clientStrictFlag;

    private Long createTimeStart;

    private Long createTimeEnd;

    private Long updateTimeStart;

    private Long updateTimeEnd;

    private String titleLike;
}
