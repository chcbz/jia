package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ability_evaluation")
public class AbilityEvaluationEntity extends BaseEntity {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String agentName;
    private String evaluationType;
    private String taskContext;
    private Integer powerScore;
    private Integer intelligenceScore;
    private Integer leadershipScore;
    private Integer abilityMatchScore;
    private Integer overallScore;
    private String evaluationContent;
    private String comment;
    private Long evaluationTime;
}
