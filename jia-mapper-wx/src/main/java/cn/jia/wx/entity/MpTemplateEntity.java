package cn.jia.wx.entity;

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
 * @since 2021-01-09
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("wx_mp_template")
@Schema(name = "MpTemplate对象")
public class MpTemplateEntity extends BaseEntity {

    @Schema(description = "模板ID")
    @TableId(value = "template_id", type = IdType.AUTO)
    private String templateId;

    @Schema(description = "应用标识码")
    private String clientId;

    @Schema(description = "开发者ID")
    private String appid;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "主要行业")
    private String primaryIndustry;

    @Schema(description = "子行业")
    private String deputyIndustry;

    @Schema(description = "模板内容")
    private String content;

    @Schema(description = "示例")
    private String example;

    @Schema(description = "状态 1有效 0无效")
    private Integer status;

}
