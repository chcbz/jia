package cn.jia.kefu.entity;

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
 * 客服消息订阅
 * </p>
 *
 * @author chc
 * @since 2021-02-18
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("kefu_msg_subscribe")
@Schema(name = "客服消息订阅", description="")
public class KefuMsgSubscribeEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID=1L;

    @Schema(description = "ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "类型编码")
    private String typeCode;

    @Schema(description = "Jia账号")
    private String jiacn;

    @Schema(description = "状态 0失效 1有效")
    private Integer status;

    @Schema(description = "微信接收")
    private Integer wxRxFlag;

    @Schema(description = "短信接收")
    private Integer smsRxFlag;
}
