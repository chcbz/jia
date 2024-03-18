package cn.jia.isp.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("cms_column")
@Schema(name = "CmsColumn对象")
public class CmsColumnEntity extends BaseEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long tableId;

    private String name;

    private String type;

    private Integer precision;

    private Integer scale;

    private Integer notnull;

    private String defaultValue;

    private String selectRange;

    private Integer isSearch;

    private Integer isList;

    private String remark;

}