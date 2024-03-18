package cn.jia.mat.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.Accessors;

import java.util.List;

@Getter
@Setter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@Schema(name = "投票问题VO")
public class MatVoteQuestionVO extends MatVoteQuestionEntity {
    private List<MatVoteItemEntity> items;
}
