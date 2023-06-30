package cn.jia.mat.entity;

import cn.jia.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 
 * </p>
 *
 * @author chc
 * @since 2021-10-29
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("mat_vote_question")
@Schema(name = "MatVoteQuestion对象", description="")
public class MatVoteQuestionEntity extends BaseEntity {

    private static final long serialVersionUID=1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @Schema(description = "投票单ID")
    private Integer voteId;

    @Schema(description = "问题")
    private String title;

    @Schema(description = "是否多选 0否 1是")
    private Integer multi;

    @Schema(description = "分值")
    private Integer point;

    @Schema(description = "实际答案")
    private String opt;


}
