package cn.jia.oauth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class WeiXinOauthUserDTO {
    @JsonProperty("errcode")
    private String errCode;

    @JsonProperty("errmsg")
    private String errMsg;

    @JsonProperty("unionid")
    private String unionId;

    private String country;

    private String province;

    private String city;

    private String nickname;

    private Integer sex;

    @JsonProperty("headimgurl")
    private String headImgUrl;
}
