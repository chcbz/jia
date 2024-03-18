package cn.jia.core.entity;

import cn.jia.core.util.DateUtil;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
    private static final long serialVersionUID= 1634677571431292849L;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private Long createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    private Long updateTime;

    @Schema(description = "租户ID")
    private String tenantId;

    @Schema(description = "应用标识符")
    private String clientId;

    @JsonIgnore
    public void init4Creation() {
        Long now = DateUtil.nowTime();
        if (this.createTime == null) {
            this.createTime = now;
        }
        if (this.updateTime == null) {
            this.updateTime = now;
        }
    }

    @JsonIgnore
    public void init4Update() {
        if (this.updateTime == null) {
            this.updateTime = DateUtil.nowTime();
        }
    }
}
