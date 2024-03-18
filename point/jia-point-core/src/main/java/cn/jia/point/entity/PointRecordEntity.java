package cn.jia.point.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

/**
 * <p>
 * 
 * </p>
 *
 * @author chc
 * @since 2021-02-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("point_record")
@Schema(name = "PointRecord对象", description="")
public class PointRecordEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID=1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "Jia账号")
    private String jiacn;

    @Schema(description = "积分类型 1新用户 2签到 3推荐 4礼品兑换 5试试手气 6答题 7短语被赞")
    private Integer type;

    @Schema(description = "积分变化")
    private Integer chg;

    @Schema(description = "剩余积分")
    private Integer remain;


}
