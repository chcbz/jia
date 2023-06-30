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
@TableName("mat_tip")
@Schema(name = "MatTip对象", description="")
public class MatTipEntity extends BaseEntity {

    private static final long serialVersionUID=1L;

    @Schema(description = "打赏ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @Schema(description = "类型 1短语")
    private Integer type;

    @Schema(description = "业务实体ID")
    private Integer entityId;

    @Schema(description = "Jia账号")
    private String jiacn;

    @Schema(description = "打赏金额（单位：分）")
    private Integer price;

    @Schema(description = "状态 0未支付 1已支付 2已发货")
    private Integer status;

    @Schema(description = "打赏时间")
    private Long time;


}
