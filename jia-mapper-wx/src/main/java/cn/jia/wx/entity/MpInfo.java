package cn.jia.wx.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("wx_mp_info")
public class MpInfo {
    @TableId(type = IdType.AUTO)
    private Integer acid;

    private String clientId;

    private String token;

    private String accessToken;

    private String encodingaeskey;

    private Byte level;

    private String name;

    private String account;

    private String original;

    private String signature;

    private String country;

    private String province;

    private String city;

    private String username;

    private String password;

    private Long createTime;

    private Long updateTime;

    private Integer status;

    private String appid;

    private String secret;

    private Integer styleid;

    private String subscribeurl;

    private String authRefreshToken;

}