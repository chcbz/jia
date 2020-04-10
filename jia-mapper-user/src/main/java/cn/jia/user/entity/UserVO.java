package cn.jia.user.entity;

import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

@Data
public class UserVO {
    private Integer id;

    private String username;

    private String openid;

    private String jiacn;

    private String phone;

    private String email;

    private Integer sex;

    private String nickname;

    private String city;

    private String country;

    private String province;

    private String latitude;

    private String longitude;

    private Integer point;

    private String referrer;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    private Date birthday;

    private String tel;

    private String weixin;

    private String qq;

    private Integer position;

    private Integer status;

    private String remark;

    private String msgType;

    private Integer createTime;

    private Integer updateTime;
    
    private List<Integer> roleIds;
    
    private List<Integer> orgIds;
}