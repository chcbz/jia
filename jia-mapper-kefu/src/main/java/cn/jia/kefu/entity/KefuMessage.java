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
 * 留言信息
 * </p>
 *
 * @author chc
 * @since 2021-01-29
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("kefu_message")
@ApiModel(value="KefuMessage对象", description="")
public class KefuMessage extends BaseEntity {

    private static final long serialVersionUID=1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @ApiModelProperty(value = "资源ID")
    private String resourceId;

    @ApiModelProperty(value = "应用标识符")
    private String clientId;

    @ApiModelProperty(value = "Jia账号")
    private String jiacn;

    @ApiModelProperty(value = "姓名")
    private String name;

    @ApiModelProperty(value = "电话号码")
    private String phone;

    @ApiModelProperty(value = "邮箱地址")
    private String email;

    private String title;

    private String content;

    private String attachment;

    @ApiModelProperty(value = "回复内容")
    private String reply;

    @ApiModelProperty(value = "状态 0待回复 1已回复")
    private Integer status;


}
