package cn.jia.core.entity;

import cn.jia.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * <p>
 * 字典数据表
 * </p>
 *
 * @author chc
 * @since 2023-06-18
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("CORE_DICT")
@Schema(name = "Dict", description = "字典数据表")
public class Dict extends BaseEntity {

    @Schema(description = "表id")
    @TableId(value = "ID", type = IdType.AUTO)
    private Integer id;

    @Schema(description = "字典类型")
    private String type;

    private String language;

    @Schema(description = "字典名称")
    private String name;

    @Schema(description = "字典值")
    private String value;

    @Schema(description = "字典文件路径")
    private String url;

    @Schema(description = "父Id")
    private String parentId;

    private Integer dictOrder;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "状态 1：有效 2：失效")
    private Byte status;
}
