package cn.jia.sms.entity;

import lombok.Data;

@Data
public class SmsSendResult {
    /**
     * 是否发送成功
     */
    private boolean success;
    
    /**
     * 结果代码
     */
    private String code;
    
    /**
     * 结果描述
     */
    private String message;
    
    /**
     * 消息ID
     */
    private String msgId;
    
    /**
     * 短信文本计费数
     */
    private Integer contNum;
}