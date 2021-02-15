package cn.jia.point.entity;

import cn.jia.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

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
@ApiModel(value="PointRecord对象", description="")
public class PointRecord extends BaseEntity {

    private static final long serialVersionUID=1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @ApiModelProperty(value = "Jia账号")
    private String jiacn;

    @ApiModelProperty(value = "积分类型 1新用户 2签到 3推荐 4礼品兑换 5试试手气 6答题 7短语被赞")
    private Integer type;

    @ApiModelProperty(value = "积分变化")
    private Integer chg;

    @ApiModelProperty(value = "剩余积分")
    private Integer remain;

    @ApiModelProperty(value = "记录时间")
    private Long time;


}
