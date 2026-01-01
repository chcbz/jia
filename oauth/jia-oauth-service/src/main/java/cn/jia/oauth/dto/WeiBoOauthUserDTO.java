package cn.jia.oauth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 微博OAuth User DTO
 */
@Getter
@Setter
@ToString
public class WeiBoOauthUserDTO {
    private String id;

    @JsonProperty("idstr")
    private String idStr;

    private String screenName;

    @JsonProperty("name")
    private String name;

    private String province;

    private String city;

    private String location;

    private String description;

    @JsonProperty("profile_image_url")
    private String profileImageUrl;

    @JsonProperty("gender")
    private String gender;

    private String remark;
}