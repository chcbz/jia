package cn.jia.oauth.entity;

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
 * OAuth API密钥实体类
 * 用于管理客户端的API密钥，支持一个客户端拥有多个API密钥
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("oauth_api_key")
@Schema(name = "OauthApiKey对象", description = "OAuth API密钥实体")
public class OauthApiKeyEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private String id;

    @Schema(description = "客户端ID")
    private String clientId;

    @Schema(description = "用户标识")
    private String jiacn;

    @Schema(description = "API密钥")
    private String apiKey;

    @Schema(description = "密钥名称")
    private String keyName;

    @Schema(description = "是否启用")
    private Integer status;

    @Schema(description = "过期时间")
    private Long expireTime;

    @Schema(description = "描述")
    private String description;
}
