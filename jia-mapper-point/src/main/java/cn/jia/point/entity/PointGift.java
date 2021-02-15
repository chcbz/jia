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
@ApiModel(value="PointGift对象", description="")
public class PointGift extends BaseEntity {

    private static final long serialVersionUID=1L;

    @ApiModelProperty(value = "礼品ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @ApiModelProperty(value = "应用标识码")
    private String clientId;

    @ApiModelProperty(value = "礼品名称")
    private String name;

    @ApiModelProperty(value = "礼品描述")
    private String description;

    @ApiModelProperty(value = "礼品图片地址")
    private String picUrl;

    @ApiModelProperty(value = "礼品所需积分")
    private Integer point;

    @ApiModelProperty(value = "价格（单位：分）")
    private Integer price;

    @ApiModelProperty(value = "礼品数量")
    private Integer quantity;

    @ApiModelProperty(value = "是否虚拟物品 0否 1是")
    private Integer virtualFlag;

    @ApiModelProperty(value = "状态 1上架 0下架")
    private Integer status;


}
