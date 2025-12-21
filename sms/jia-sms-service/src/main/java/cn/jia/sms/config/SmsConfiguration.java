package cn.jia.sms.config;

import cn.jia.sms.service.impl.AliyunSmsServiceImpl;
import cn.jia.sms.service.impl.ZthySmsServiceImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SmsConfiguration {

    @Bean("aliyunSmsService")
    @ConditionalOnProperty(name = "sms.provider.type", havingValue = "aliyun")
    public AliyunSmsServiceImpl aliyunSmsService() {
        return new AliyunSmsServiceImpl();
    }

    @Bean("zthySmsService")
    @ConditionalOnProperty(name = "sms.provider.type", havingValue = "zthysms")
    public ZthySmsServiceImpl zthySmsService() {
        return new ZthySmsServiceImpl();
    }
}