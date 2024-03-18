package cn.jia.sms.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(callSuper = true)
public class SmsMessageVO extends SmsMessageEntity {

    private String token;

    private String appid;
}
