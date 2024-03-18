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
@TableName("point_gift")
@Schema(name = "PointGift对象", description="")
public class PointGiftEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID=1L;

    @Schema(description = "礼品ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "礼品名称")
    private String name;

    @Schema(description = "礼品描述")
    private String description;

    @Schema(description = "礼品图片地址")
    private String picUrl;

    @Schema(description = "礼品所需积分")
    private Integer point;

    @Schema(description = "价格（单位：分）")
    private Integer price;

    @Schema(description = "礼品数量")
    private Integer quantity;

    @Schema(description = "是否虚拟物品 0否 1是")
    private Integer virtualFlag;

    @Schema(description = "状态 1上架 0下架")
    private Integer status;


}
