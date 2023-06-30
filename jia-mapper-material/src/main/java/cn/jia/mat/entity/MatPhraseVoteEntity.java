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
@TableName("mat_phrase_vote")
@Schema(name = "MatPhraseVote对象", description="")
public class MatPhraseVoteEntity extends BaseEntity {

    private static final long serialVersionUID=1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @Schema(description = "用户JIA账户")
    private String jiacn;

    @Schema(description = "短语表ID")
    private Integer phraseId;

    @Schema(description = "投票情况 1赞 0踩")
    private Integer vote;

    @Schema(description = "时间")
    private Long time;


}
