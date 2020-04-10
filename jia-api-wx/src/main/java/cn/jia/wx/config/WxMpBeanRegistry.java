package cn.jia.wx.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

import me.chanjar.weixin.mp.api.WxMpInMemoryConfigStorage;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.api.impl.WxMpServiceImpl;

/**
 * 项目启动时注册非注解类到Spring容器
 * 
 * @author Administrator
 * @date 2018年1月15日 上午9:57:33
 */
/*@Configuration*/
public class WxMpBeanRegistry {
	@Autowired
	private WxMpInMemoryConfigStorage config;
	
	@Bean
    @ConfigurationProperties(prefix = "wxmp")
    public WxMpInMemoryConfigStorage wxMpInMemoryConfigStorage(){
       return new WxMpInMemoryConfigStorage();
    }
	
	@Bean
	public WxMpService wxMpService(){
		WxMpService wxMpService = new WxMpServiceImpl();
		wxMpService.setWxMpConfigStorage(config);
		return wxMpService;
	}
}
