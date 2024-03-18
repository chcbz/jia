package cn.jia.mat.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(callSuper = true)
public class MatMediaReqVO extends MatMediaEntity {
    private Integer clientStrictFlag;

    private Long timeStart;

    private Long timeEnd;

    private String titleLike;
}
