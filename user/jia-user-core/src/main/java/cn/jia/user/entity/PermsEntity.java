package cn.jia.user.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

/**
 * <p>
 * 
 * </p>
 *
 * @author chc
 * @since 2021-11-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("user_perms")
@Schema(name = "UserPerms对象", description="")
public class PermsEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID=1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "模块")
    private String module;

    @Schema(description = "方法")
    private String func;

    @Schema(description = "服务地址")
    private String url;

    @Schema(description = "备注")
    private String description;

    @Schema(description = "数据来源 1系统生成 2手动创建")
    private Integer source;

    @Schema(description = "权限级别 1总后台 2企业后台")
    private Integer level;

    @Schema(description = "状态(1:正常,0:停用)")
    private Integer status;

}
