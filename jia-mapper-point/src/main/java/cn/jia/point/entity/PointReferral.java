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
@ApiModel(value="PointReferral对象", description="")
public class PointReferral extends BaseEntity {

    private static final long serialVersionUID=1L;

    @ApiModelProperty(value = "推荐ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @ApiModelProperty(value = "推荐人Jia账号")
    private String referrer;

    @ApiModelProperty(value = "被推荐人Jia账号")
    private String referral;

    @ApiModelProperty(value = "时间")
    private Long time;


}
