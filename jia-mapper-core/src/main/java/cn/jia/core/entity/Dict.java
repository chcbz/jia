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
@TableName("core_dict")
@ApiModel(value="Dict对象", description="")
public class Dict extends BaseEntity {

    private static final long serialVersionUID=1L;

    @ApiModelProperty(value = "表id")
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @ApiModelProperty(value = "字典类型")
    private String type;

    private String language;

    @ApiModelProperty(value = "字典名称")
    private String name;

    @ApiModelProperty(value = "字典值")
    private String value;

    @ApiModelProperty(value = "字典文件路径")
    private String url;

    @ApiModelProperty(value = "父Id")
    private String parentId;

    private Integer dictOrder;

    @ApiModelProperty(value = "描述")
    private String description;

    @ApiModelProperty(value = "状态 1：有效 2：失效")
    private Integer status;


}
