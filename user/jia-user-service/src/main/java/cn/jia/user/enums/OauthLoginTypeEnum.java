package cn.jia.user.enums;

import lombok.Getter;

public enum OauthLoginTypeEnum {
    WX_MP("wxmp", "微信公众号"),

    WEI_XIN("weixin", "微信平台"),

    WEI_BO("weibo", "微博平台");

    @Getter
    private final String code;
    @Getter
    private final String name;

    OauthLoginTypeEnum(String code, String name) {
        this.code = code;
        this.name = name;
    }
}
