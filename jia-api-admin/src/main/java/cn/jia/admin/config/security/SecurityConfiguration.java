package cn.jia.admin.config.security;

import cn.jia.core.configuration.SpringContextHolder;
import cn.jia.core.entity.Action;
import cn.jia.core.util.MD5Util;
import cn.jia.core.util.StringUtils;
import cn.jia.sms.entity.SmsCode;
import cn.jia.sms.service.SmsService;
import cn.jia.user.common.Constants;
import cn.jia.user.entity.Org;
import cn.jia.user.entity.Role;
import cn.jia.user.entity.User;
import cn.jia.user.service.OrgService;
import cn.jia.user.service.RoleService;
import cn.jia.user.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.encoding.Md5PasswordEncoder;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.authentication.OAuth2AuthenticationDetails;
import org.springframework.security.oauth2.provider.token.TokenStore;
import org.springframework.security.oauth2.provider.token.store.redis.RedisTokenStore;
import org.springframework.web.client.RestTemplate;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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

	@Autowired
	private UserService userService;
	@Autowired
	private OrgService orgService;
	@Autowired
	private RoleService roleService;
	/*@Autowired
	private ClientService clientService;*/
	@Autowired
	private RedisTemplate<String, Object> redisTemplate;
	@Autowired
	private RestTemplate restTemplate;
	@Autowired
	private SmsService smsService;

	@Bean
	public UserDetailsService userDetailsService() {
		return new UserDetailsService() {
			/**
			 * 根据用户名获取登录用户信息
			 * 
			 * @param username
			 * @return
			 * @throws UsernameNotFoundException
			 */
			@Override
			public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
				User user;
				if(username.startsWith("wx-")) { //微信登录
					user = userService.findByOpenid(username.substring(3));
				}
				else if(username.startsWith("mb-")) {
					user = userService.findByPhone(username.substring(3));
				}
				else {
					user = userService.findByUsername(username);
				}

				if (user == null) {
					throw new UsernameNotFoundException("用户名：" + username + "不存在！");
				}

				// 获取用户的所有权限并且SpringSecurity需要的集合
				Collection<GrantedAuthority> grantedAuthorities = new ArrayList<>();
				Org org = orgService.find(user.getPosition());
				if(org != null) {
					for (Role role : roleService.listByUserId(user.getId(), org.getClientId(), 1, Integer.MAX_VALUE)) {
						List<Action> perms = roleService.listPerms(role.getId(), 1, Integer.MAX_VALUE);
						for(Action p : perms) {
							if(Constants.PERMS_STATUS_ENABLE.equals(p.getStatus())) {
								GrantedAuthority grantedAuthority = new SimpleGrantedAuthority(p.getModule()+"-"+p.getFunc());
								grantedAuthorities.add(grantedAuthority);
							}
						}
					}
					//设置登录用户所属clientId
					redisTemplate.opsForValue().set("clientId_" + username, org.getClientId());
				}
				
				String password = user.getPassword();
				//微信登录的话采用特定密码进行验证
				if(username.startsWith("wx-")) {
					password = "wxpwd";
				}else if(username.startsWith("mb-")) {
					SmsCode code = smsService.selectSmsCodeNoUsed(username.substring(3), Constants.SMS_TYPE_CODE, org != null ? org.getClientId() : "jia_client");
					if(code != null) {
						smsService.useSmsCode(code.getId());
						password = code.getSmsCode();
					}
				}

				return new org.springframework.security.core.userdetails.User(username, MD5Util.str2Base32MD5(password), grantedAuthorities);
			}
		};
	}

	/**
	 * 获取当前登录用户信息
	 * @return
	 */
	public static User tokenUser() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getDetails() instanceof OAuth2AuthenticationDetails) {
			UserService userService = SpringContextHolder.getBean(UserService.class);
			String username = authentication.getName();
			if (username.startsWith("wx-")) {
				return userService.findByOpenid(username.substring(3));
			} else if (username.startsWith("mb-")) {
				return userService.findByPhone(username.substring(3));
			} else {
				return userService.findByUsername(username);
			}
		} else {
			return null;
		}
	}
	public static User tokenUser(String token) {
		RedisConnectionFactory redisConnection = SpringContextHolder.getBean(RedisConnectionFactory.class);
		TokenStore tokenStore = new RedisTokenStore(redisConnection);
		OAuth2Authentication authentication = tokenStore.readAuthentication(token);
		if (authentication != null && StringUtils.isNotEmpty(authentication.getName())) {
			UserService userService = SpringContextHolder.getBean(UserService.class);
			String username = authentication.getName();
			if (username.startsWith("wx-")) {
				return userService.findByOpenid(username.substring(3));
			} else if (username.startsWith("mb-")) {
				return userService.findByPhone(username.substring(3));
			} else {
				return userService.findByUsername(username);
			}
		} else {
			return null;
		}
	}
	
	/*@Bean
	public ClientDetailsService clientDetailsService() {
		return clientId -> {
			Client details = clientService.find(clientId);
			//设置appcn
			redisTemplate.opsForValue().set("appcn_" + clientId, details.getAppcn());
			return details;
		};
	}*/
	
	@Bean
	public PermissionEvaluator permissionEvaluator() {
		return new PermissionEvaluator() {

			@Override
			public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
				// TODO Auto-generated method stub
				return false;
			}

			@Override
			public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType,
					Object permission) {
				return false;
			}
			
		};
		
	}
    
    //配置全局设置
    @Autowired
    public void configureGlobal(AuthenticationManagerBuilder auth) throws Exception {
    	auth.userDetailsService(userDetailsService()).passwordEncoder(new Md5PasswordEncoder());
    }
    @Override
    @Bean
    public AuthenticationManager authenticationManagerBean() throws Exception {
        return super.authenticationManagerBean();
    }
    @Override
	protected void configure(HttpSecurity http) throws Exception {
//		http.authorizeRequests().anyRequest().authenticated().and().formLogin().and().httpBasic();
    	http.requestMatchers().antMatchers(HttpMethod.OPTIONS, "/oauth/token", "/**") //解决浏览器跨域请求时的权限问题
    			.and().csrf().disable();
	}
}
