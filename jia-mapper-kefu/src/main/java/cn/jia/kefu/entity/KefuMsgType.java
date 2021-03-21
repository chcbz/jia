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
 * 
 * </p>
 *
 * @author chc
 * @since 2021-03-21
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("kefu_msg_type")
@ApiModel(value="KefuMsgType对象", description="")
public class KefuMsgType extends BaseEntity {

    private static final long serialVersionUID=1L;

    @ApiModelProperty(value = "ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @ApiModelProperty(value = "应用标识符")
    private String clientId;

    @ApiModelProperty(value = "类型编码")
    private String typeCode;

    @ApiModelProperty(value = "类型名称")
    private String typeName;

    @ApiModelProperty(value = "父类型")
    private String parentType;

    @ApiModelProperty(value = "类别")
    private String typeCategory;

    @ApiModelProperty(value = "微信模板ID")
    private String wxTemplateId;

    @ApiModelProperty(value = "微信模板")
    private String wxTemplate;

    @ApiModelProperty(value = "短信模板ID")
    private String smsTemplateId;

    @ApiModelProperty(value = "短信模板")
    private String smsTemplate;

    @ApiModelProperty(value = "状态 0失效 1有效")
    private Integer status;


}
