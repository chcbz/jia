package cn.jia.sms.entity;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class SmsBatchRecord {
    /**
     * 手机号码
     */
    private String mobile;

    /**
     * 短信内容
     */
    private String content;

    /**
     * 扩展号
     */
    private String ext;

    /**
     * 自定义参数
     */
    private String extend;
}