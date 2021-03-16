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
 * 常见问题
 * </p>
 *
 * @author chc
 * @since 2021-01-29
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("kefu_faq")
@ApiModel(value="KefuFaq对象", description="")
public class KefuFaq extends BaseEntity {

    private static final long serialVersionUID=1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @ApiModelProperty(value = "类型")
    private String type;

    @ApiModelProperty(value = "资源ID")
    private String resourceId;

    @ApiModelProperty(value = "应用标识符")
    private String clientId;

    @ApiModelProperty(value = "标题")
    private String title;

    @ApiModelProperty(value = "内容")
    private String content;

    @ApiModelProperty(value = "点击量")
    private Integer click;

    @ApiModelProperty(value = "点赞数量")
    private Integer useful;

    @ApiModelProperty(value = "点踩数量")
    private Integer useless;

    @ApiModelProperty(value = "状态 0无效 1有效")
    private Integer status;


}
