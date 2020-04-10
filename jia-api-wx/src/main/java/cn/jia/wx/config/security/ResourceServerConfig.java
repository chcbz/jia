package cn.jia.wx.config.security;

import java.io.PrintWriter;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.common.exceptions.InvalidTokenException;
import org.springframework.security.oauth2.config.annotation.web.configuration.EnableResourceServer;
import org.springframework.security.oauth2.config.annotation.web.configuration.ResourceServerConfigurerAdapter;
import org.springframework.security.oauth2.config.annotation.web.configurers.ResourceServerSecurityConfigurer;
import org.springframework.security.oauth2.provider.token.RemoteTokenServices;
import org.springframework.security.oauth2.provider.token.ResourceServerTokenServices;
import org.springframework.web.client.RestTemplate;

import cn.jia.core.entity.JSONResult;
import cn.jia.core.util.JSONUtil;
import cn.jia.wx.common.ErrorConstants;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@EnableResourceServer
public class ResourceServerConfig extends ResourceServerConfigurerAdapter {
	
	@Value("${security.oauth2.resource.id}")
	private String resourceId;
	@Value("${security.oauth2.resource.token-info-uri}")
	private String checkTokenEndpointUrl;
	@Value("${security.oauth2.client.client-id}")
	private String clientId;
	@Value("${security.oauth2.client.client-secret}")
	private String clientSecret;
	
	@Autowired
	private RestTemplate restTemplate;
	
	@Override
	public void configure(HttpSecurity http) throws Exception {
		http.csrf().disable().exceptionHandling().authenticationEntryPoint((request, response, authException) -> {
			log.warn(authException.getMessage(), authException);
			JSONResult<Object> result = new JSONResult<>();
			result.setMsg(authException.getMessage());
			result.setCode(ErrorConstants.UNAUTHORIZED);
			result.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setCharacterEncoding("UTF-8");
			response.setContentType("application/json; charset=utf-8");
			response.setHeader("Access-Control-Allow-Origin", "*");
			PrintWriter out = response.getWriter();
			out.print(JSONUtil.toJson(result));
		}).and().authorizeRequests()
				.antMatchers("/wx/mp/checksignature", "/wx/pay/parseScanPayNotifyResult",
						"/wx/pay/parseOrderNotifyResult", "/wx/mp/oauth2/access_token")
				.permitAll().and().authorizeRequests().anyRequest().authenticated().and().httpBasic();
	}
	
	@Override
	public void configure(ResourceServerSecurityConfigurer resources) throws Exception {
		resources.authenticationEntryPoint((request, response, authException) -> {
			log.warn(authException.getMessage(), authException);
			JSONResult<Object> result = new JSONResult<>();
			Throwable throwable = authException.getCause();
	        if (throwable instanceof InvalidTokenException) {
	        	result.setMsg("invalid token: " + authException.getMessage());
	        }else {
	        	result.setMsg(authException.getMessage());
	        }
			result.setCode(ErrorConstants.UNAUTHORIZED);
			result.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setCharacterEncoding("UTF-8");
			response.setContentType("application/json; charset=utf-8");
			response.setHeader("Access-Control-Allow-Origin", "*");
			PrintWriter out = response.getWriter();
			out.print(JSONUtil.toJson(result));
		}).tokenServices(tokenServices()).resourceId(resourceId);
	}
	
	@Bean
	public ResourceServerTokenServices tokenServices() {
		RemoteTokenServices remoteTokenServices = new RemoteTokenServices();
		// 这里配置远程校验token的地址，（也可以使用其他方式，例如本地校验）
		remoteTokenServices.setCheckTokenEndpointUrl(checkTokenEndpointUrl);
		// 为方便测试使用硬编码，要和security-server配置的相同（security服务会校验客户端信息）
		remoteTokenServices.setClientId(clientId);
		remoteTokenServices.setClientSecret(clientSecret);
		remoteTokenServices.setRestTemplate(restTemplate);
		return remoteTokenServices;
	}
}