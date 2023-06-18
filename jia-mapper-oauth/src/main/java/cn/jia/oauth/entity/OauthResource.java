package cn.jia.oauth.entity;

import cn.jia.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 
 * </p>
 *
 * @author chc
 * @since 2021-11-07
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("oauth_resource")
@Schema(name = "OauthResource对象", description="")
public class OauthResource extends BaseEntity {

    private static final long serialVersionUID=1L;

    @Schema(description = "资源ID")
    @TableId(value = "resource_id", type = IdType.AUTO)
    private String resourceId;

    @Schema(description = "资源名称")
    private String resourceName;

    @Schema(description = "资源描述")
    private String resourceDescription;

    @Schema(description = "状态 0无效 1有效")
    private Integer status;


}
