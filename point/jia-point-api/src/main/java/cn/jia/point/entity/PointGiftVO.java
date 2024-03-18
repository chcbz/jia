package cn.jia.point.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class PointGiftVO extends PointGiftEntity {

    private Integer clientStrictFlag;

    private Long createTimeStart;

    private Long createTimeEnd;

    private Long updateTimeStart;

    private Long updateTimeEnd;

    private String nameLike;

    private String descriptionLike;
}