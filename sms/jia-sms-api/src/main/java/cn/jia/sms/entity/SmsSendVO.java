package cn.jia.sms.entity;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SmsSendVO extends SmsSendEntity {

    private Long timeStart;

    private Long timeEnd;
}
