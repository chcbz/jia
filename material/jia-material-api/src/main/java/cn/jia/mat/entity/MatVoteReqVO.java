package cn.jia.mat.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(callSuper = true)
public class MatVoteReqVO extends MatVoteEntity {
    private String nameLike;
}
