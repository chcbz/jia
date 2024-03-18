package cn.jia.sms.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(callSuper = true)
public class SmsTemplateVO extends SmsTemplateEntity {

    private Integer clientStrictFlag;

    private Long createTimeStart;

    private Long createTimeEnd;

    private Long updateTimeStart;

    private Long updateTimeEnd;

    private String nameLike;
}
