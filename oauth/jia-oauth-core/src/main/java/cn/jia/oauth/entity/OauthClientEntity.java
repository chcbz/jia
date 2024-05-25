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
import java.util.Date;

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
@TableName("oauth_client")
@Schema(name = "OauthClient对象", description="")
public class OauthClientEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID=1L;

    @TableId(value = "id", type = IdType.AUTO)
    private String id;

    private String clientId;

    private Date clientIdIssuedAt;

    private String clientSecret;

    private Date clientSecretExpiresAt;

    private String clientName;

    private String clientAuthenticationMethods;

    @Schema(description = "应用标识符")
    private String appcn;

    private String authorizationGrantTypes;

    private String redirectUris;

    private String postLogoutRedirectUris;

    private String scopes;

    private String clientSettings;

    private String tokenSettings;

}
