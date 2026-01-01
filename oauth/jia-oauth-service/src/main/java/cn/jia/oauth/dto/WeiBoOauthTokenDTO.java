package cn.jia.oauth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 微博OAuth Token DTO
 */
@Getter
@Setter
@ToString
public class WeiBoOauthTokenDTO {
    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("remind_in")
    private String remindIn;

    @JsonProperty("expires_in")
    private String expiresIn;

    private String uid;
}