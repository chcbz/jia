package cn.jia.oauth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * GitHub OAuth Token DTO
 * 用于处理GitHub OAuth授权码换取访问令牌的响应
 */
@Getter
@Setter
@ToString
public class GithubOauthTokenDTO {
    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("token_type")
    private String tokenType;

    @JsonProperty("scope")
    private String scope;
}