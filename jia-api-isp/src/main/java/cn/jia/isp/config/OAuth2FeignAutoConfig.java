package cn.jia.isp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.provider.authentication.OAuth2AuthenticationDetails;

import feign.RequestInterceptor;
import feign.RequestTemplate;

@Configuration
public class OAuth2FeignAutoConfig {
	@Bean
	public RequestInterceptor requestTokenBearerInterceptor() {
	    return new RequestInterceptor() {
	        @Override
	        public void apply(RequestTemplate requestTemplate) {
	            OAuth2AuthenticationDetails details = (OAuth2AuthenticationDetails)
	                    SecurityContextHolder.getContext().getAuthentication().getDetails();

	            requestTemplate.header("Authorization", "Bearer " + details.getTokenValue());
	        }
	    };
	}
}
