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
@TableName("mat_vote")
@Schema(name = "MatVote对象", description="")
public class MatVoteEntity extends BaseEntity {

    private static final long serialVersionUID=1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @Schema(description = "应用标识码")
    private String clientId;

    @Schema(description = "投票名称")
    private String name;

    @Schema(description = "发布时间")
    private Long startTime;

    @Schema(description = "截至时间")
    private Long closeTime;

    @Schema(description = "投票人数")
    private Integer num;


}
