package cn.jia.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * <p>
 * 基础实体类
 * </p>
 *
 * @author chc
 * @since 2021-01-16
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Schema(name="BaseEntity")
public class BaseEntity implements Serializable {
    @Serial
    private static final long serialVersionUID=1L;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建日期")
    private Long createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新日期")
    private Long updateTime;
}
