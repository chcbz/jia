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
@TableName("oauth_client")
@Schema(name = "OauthClient对象", description="")
public class OauthClient extends BaseEntity {

    private static final long serialVersionUID=1L;

    @TableId(value = "client_id", type = IdType.AUTO)
    private String clientId;

    private String clientSecret;

    @Schema(description = "应用标识码")
    private String appcn;

    private String resourceIds;

    private String authorizedGrantTypes;

    private String registeredRedirectUris;

    private String scope;

    private String autoapprove;

    private Integer accessTokenValiditySeconds;

    private Integer refreshTokenValiditySeconds;


}
