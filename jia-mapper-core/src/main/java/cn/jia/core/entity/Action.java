package cn.jia.core.entity;

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
 * @since 2021-10-02
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("core_action")
@ApiModel(value="Action对象", description="")
public class Action extends BaseEntity {

    private static final long serialVersionUID=1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @ApiModelProperty(value = "应用标识符")
    private String resourceId;

    @ApiModelProperty(value = "模块")
    private String module;

    @ApiModelProperty(value = "方法")
    private String func;

    @ApiModelProperty(value = "服务地址")
    private String url;

    @ApiModelProperty(value = "备注")
    private String description;

    @ApiModelProperty(value = "数据来源 1系统生成 2手动创建")
    private Integer source;

    @ApiModelProperty(value = "权限级别 1总后台 2企业后台")
    private Integer level;

    @ApiModelProperty(value = "状态(1:正常,0:停用)")
    private Integer status;


}
