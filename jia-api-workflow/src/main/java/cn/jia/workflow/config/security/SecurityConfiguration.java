package cn.jia.workflow.config.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;

/**
 * 
 * @author chcbz
 * @date 2018年6月6日 下午6:36:34
 */
@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
@Order(-1)
public class SecurityConfiguration extends WebSecurityConfigurerAdapter {
    
    @Override
	protected void configure(HttpSecurity http) throws Exception {
//		http.authorizeRequests().anyRequest().authenticated().and().formLogin().and().httpBasic();
    	http.requestMatchers().antMatchers(HttpMethod.OPTIONS, "/oauth/token", "/**") //解决浏览器跨域请求时的权限问题
        .and().csrf().disable();
	}
}
