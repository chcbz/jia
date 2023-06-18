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
@TableName("mat_vote_tick")
@Schema(name = "MatVoteTick对象", description="")
public class MatVoteTick extends BaseEntity {

    private static final long serialVersionUID=1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @Schema(description = "用户JIA账户")
    private String jiacn;

    @Schema(description = "投票ID")
    private Integer voteId;

    @Schema(description = "问题ID")
    private Integer questionId;

    @Schema(description = "选项")
    private String opt;

    @Schema(description = "是否正确 1是 0否")
    private Integer tick;

    @Schema(description = "时间")
    private Long time;


}
