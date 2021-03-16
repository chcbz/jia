package cn.jia.kefu.entity;

import cn.jia.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

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
@ApiModel(value="客服消息订阅", description="")
public class KefuMsgSubscribe extends BaseEntity {

    private static final long serialVersionUID=1L;

    @ApiModelProperty(value = "ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @ApiModelProperty(value = "应用标识符")
    private String clientId;

    @ApiModelProperty(value = "类型编码")
    private String typeCode;

    @ApiModelProperty(value = "Jia账号")
    private String jiacn;

    @ApiModelProperty(value = "状态 0失效 1有效")
    private Integer status;

    @ApiModelProperty(value = "微信接收")
    private Integer wxRxFlag;

    @ApiModelProperty(value = "短信接收")
    private Integer smsRxFlag;
}
