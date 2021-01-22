package cn.jia.wx.entity;

import cn.jia.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
 * @since 2021-01-09
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("wx_mp_template")
@ApiModel(value="MpTemplate对象")
public class MpTemplate extends BaseEntity {

    @ApiModelProperty(value = "模板ID")
    @TableId(value = "template_id", type = IdType.AUTO)
    private String templateId;

    @ApiModelProperty(value = "应用标识码")
    private String clientId;

    @ApiModelProperty(value = "开发者ID")
    private String appid;

    @ApiModelProperty(value = "标题")
    private String title;

    @ApiModelProperty(value = "主要行业")
    private String primaryIndustry;

    @ApiModelProperty(value = "子行业")
    private String deputyIndustry;

    @ApiModelProperty(value = "模板内容")
    private String content;

    @ApiModelProperty(value = "示例")
    private String example;

    @ApiModelProperty(value = "状态 1有效 0无效")
    private Integer status;

}
