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
 * 
 * </p>
 *
 * @author chc
 * @since 2021-06-27
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("kefu_msg_type")
@Schema(name = "KefuMsgType对象", description="")
public class KefuMsgTypeEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID=1L;

    @Schema(description = "ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "类型编码")
    private String typeCode;

    @Schema(description = "类型名称")
    private String typeName;

    @Schema(description = "父类型")
    private String parentType;

    @Schema(description = "类别")
    private String typeCategory;

    @Schema(description = "微信模板ID")
    private String wxTemplateId;

    @Schema(description = "微信模板")
    private String wxTemplate;

    @Schema(description = "微信文本模板")
    private String wxTemplateTxt;

    @Schema(description = "短信模板ID")
    private String smsTemplateId;

    @Schema(description = "短信模板")
    private String smsTemplate;

    @Schema(description = "链接地址")
    private String url;

    @Schema(description = "状态 0失效 1有效")
    private Integer status;


}
