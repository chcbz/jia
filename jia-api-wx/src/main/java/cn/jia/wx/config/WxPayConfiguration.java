package cn.jia.wx.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

import com.github.binarywang.wxpay.config.WxPayConfig;
import com.github.binarywang.wxpay.service.WxPayService;
import com.github.binarywang.wxpay.service.impl.WxPayServiceImpl;

/*@Configuration
@ConditionalOnClass(WxPayService.class)*/
public class WxPayConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @ConfigurationProperties(prefix = "wxpay")
  public WxPayConfig payConfig() {
    return new WxPayConfig();
  }

  @Bean
  //@ConditionalOnMissingBean
  public WxPayService wxPayService(WxPayConfig payConfig) {
    WxPayService wxPayService = new WxPayServiceImpl();
    wxPayService.setConfig(payConfig);
    return wxPayService;
  }

}