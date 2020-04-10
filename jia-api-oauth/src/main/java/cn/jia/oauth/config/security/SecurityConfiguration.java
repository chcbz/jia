package cn.jia.oauth.config.security;

import cn.jia.core.entity.JSONResult;
import cn.jia.core.util.JSONUtil;
import cn.jia.core.util.StringUtils;
import cn.jia.oauth.entity.Client;
import cn.jia.oauth.service.ClientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.encoding.PasswordEncoder;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.codec.Base64;
import org.springframework.security.crypto.codec.Utf8;
import org.springframework.security.oauth2.provider.ClientDetailsService;
import org.springframework.util.Assert;
import org.springframework.web.client.RestTemplate;

import java.io.PrintWriter;
import java.io.Serializable;
import java.security.MessageDigest;

/**
 * 
 * @author chcbz 2019-07-25
 */
@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
@Order(-1)
public class SecurityConfiguration extends WebSecurityConfigurerAdapter {

	@Autowired
	private ClientService clientService;
	@Autowired
	private RedisTemplate<String, Object> redisTemplate;
	@Autowired
	private RestTemplate restTemplate;

//	@Bean
//	public UserDetailsService userDetailsService() {
//		return new UserDetailsService() {
//			/**
//			 * 根据用户名获取登录用户信息
//			 *
//			 * @param username
//			 * @return
//			 * @throws UsernameNotFoundException
//			 */
//			@Override
//			public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
//				User user;
//				if(username.startsWith("wx-")) { //微信登录
//					user = userService.findByOpenid(username.substring(3));
//				}
//				else if(username.startsWith("mb-")) {
//					user = userService.findByPhone(username.substring(3));
//				}
//				else {
//					user = userService.findByUsername(username);
//				}
//
//				if (user == null) {
//					throw new UsernameNotFoundException("用户名：" + username + "不存在！");
//				}
//
//				// 获取用户的所有权限并且SpringSecurity需要的集合
//				Collection<GrantedAuthority> grantedAuthorities = new ArrayList<>();
//				Org org = orgService.find(user.getPosition());
//				if(org != null) {
//					for (Role role : roleService.findByUserId(user.getId(), org.getClientId())) {
//						List<Action> perms = roleService.listPerms(role.getId());
//						for(Action p : perms) {
//							if(Constants.PERMS_STATUS_ENABLE.equals(p.getStatus())) {
//								GrantedAuthority grantedAuthority = new SimpleGrantedAuthority(p.getModule()+"-"+p.getFunc());
//								grantedAuthorities.add(grantedAuthority);
//							}
//						}
//					}
//					//设置登录用户所属clientId
//					redisTemplate.opsForValue().set("clientId_" + username, org.getClientId());
//				}
//
//				String password = user.getPassword();
//				//微信登录的话采用特定密码进行验证
//				if(username.startsWith("wx-")) {
//					password = "wxpwd";
//				}else if(username.startsWith("mb-")) {
//					@SuppressWarnings("unchecked")
//					JSONResult<String> sms = restTemplate.getForObject("http://jia-api-sms/sms/use?phone={phone}&smsType={smsType}&access_token={access_token}", JSONResult.class, username.substring(3), Constants.SMS_TYPE_CODE, EsSecurityHandler.jiaToken());
//					if(ErrorConstants.SUCCESS.equals(sms.getCode())) {
//						password = sms.getData();
//					}
//				}
//
//				return new org.springframework.security.core.userdetails.User(username, MD5Util.str2Base32MD5(password), grantedAuthorities);
//			}
//		};
//	}
	
	@Bean
	public ClientDetailsService clientDetailsService() {
		return clientId -> {
			Client details = clientService.find(clientId);
			//设置appcn
			redisTemplate.opsForValue().set("appcn_" + clientId, details.getAppcn());
			return details;
		};
	}

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

    @Autowired
    public void configureGlobal(AuthenticationManagerBuilder auth) throws Exception {
    	auth.ldapAuthentication().userDnPatterns("uid={0},ou=users,dc=jia")
				.groupSearchBase("ou=groups,dc=jia").groupSearchFilter("(member={0})")
				.contextSource().url("ldap://ldap.wydiy.com:389/dc=wydiy,dc=com")
				.and().passwordCompare().passwordEncoder(new EsPasswordEncoder()).passwordAttribute("userPassword");
    }
    @Override
    @Bean
    public AuthenticationManager authenticationManagerBean() throws Exception {
        return super.authenticationManagerBean();
    }
    @Override
	protected void configure(HttpSecurity http) throws Exception {
		http.csrf().disable();
		http.requestMatchers().antMatchers("/oauth/authorize", "/oauth/confirm_access", "/oauth/login",
				"/oauth/register", "/oauth/authentication", "/oauth/third-party/**", "/oauth/logout/**", "/oauth/resetPassword")
				.and().authorizeRequests().antMatchers("/oauth/login", "/oauth/register", "/oauth/resetPassword",
				"/oauth/authentication", "/oauth/third-party/**").permitAll()
				.antMatchers(HttpMethod.OPTIONS).permitAll().anyRequest().authenticated()
				.and().formLogin().loginPage("/oauth/login").loginProcessingUrl("/oauth/authentication")
				.and().logout().logoutUrl("/oauth/logout").logoutSuccessHandler((request, response, authentication) -> {
					String backUrl = request.getParameter("redirect_uri");
					if(StringUtils.isNotEmpty(backUrl)) {
						response.sendRedirect(backUrl);
					} else {
						JSONResult<Object> result = JSONResult.success(authentication);
						response.setCharacterEncoding("UTF-8");
						response.setContentType("application/json; charset=utf-8");
						response.setHeader("Access-Control-Allow-Origin", "*");
						PrintWriter out = response.getWriter();
						out.print(JSONUtil.toJson(result));
					}
				});
	}

	static class EsPasswordEncoder implements PasswordEncoder {
		// ~ Static fields/initializers
		// =====================================================================================

		/** The number of bytes in a SHA hash */
		private static final int SHA_LENGTH = 20;
		private static final String SSHA_PREFIX = "{SSHA}";
		private static final String SSHA_PREFIX_LC = SSHA_PREFIX.toLowerCase();
		private static final String SHA_PREFIX = "{SHA}";
		private static final String SHA_PREFIX_LC = SHA_PREFIX.toLowerCase();

		// ~ Instance fields
		// ================================================================================================
		private boolean forceLowerCasePrefix;

		// ~ Constructors
		// ===================================================================================================

		EsPasswordEncoder() {
		}

		// ~ Methods
		// ========================================================================================================

		private byte[] combineHashAndSalt(byte[] hash, byte[] salt) {
			if (salt == null) {
				return hash;
			}

			byte[] hashAndSalt = new byte[hash.length + salt.length];
			System.arraycopy(hash, 0, hashAndSalt, 0, hash.length);
			System.arraycopy(salt, 0, hashAndSalt, hash.length, salt.length);

			return hashAndSalt;
		}

		/**
		 * Calculates the hash of password (and salt bytes, if supplied) and returns a base64
		 * encoded concatenation of the hash and salt, prefixed with {SHA} (or {SSHA} if salt
		 * was used).
		 *
		 * @param rawPass the password to be encoded.
		 * @param salt the salt. Must be a byte array or null.
		 *
		 * @return the encoded password in the specified format
		 *
		 */
		public String encodePassword(String rawPass, Object salt) {
			if(extractPrefix(rawPass) != null){
				return rawPass;
			}
			MessageDigest sha;

			try {
				sha = MessageDigest.getInstance("SHA");
				sha.update(Utf8.encode(rawPass));
			}
			catch (java.security.NoSuchAlgorithmException e) {
				throw new IllegalStateException("No SHA implementation available!");
			}

			if (salt != null) {
				Assert.isInstanceOf(byte[].class, salt, "Salt value must be a byte array");
				sha.update((byte[]) salt);
			}

			byte[] hash = combineHashAndSalt(sha.digest(), (byte[]) salt);

			String prefix;

			if (salt == null) {
				prefix = forceLowerCasePrefix ? SHA_PREFIX_LC : SHA_PREFIX;
			}
			else {
				prefix = forceLowerCasePrefix ? SSHA_PREFIX_LC : SSHA_PREFIX;
			}

			return prefix + Utf8.decode(Base64.encode(hash));
		}

		private byte[] extractSalt(String encPass) {
			String encPassNoLabel = encPass.substring(6);

			byte[] hashAndSalt = Base64.decode(encPassNoLabel.getBytes());
			int saltLength = hashAndSalt.length - SHA_LENGTH;
			byte[] salt = new byte[saltLength];
			System.arraycopy(hashAndSalt, SHA_LENGTH, salt, 0, saltLength);

			return salt;
		}

		/**
		 * Checks the validity of an unencoded password against an encoded one in the form
		 * "{SSHA}sQuQF8vj8Eg2Y1hPdh3bkQhCKQBgjhQI".
		 *
		 * @param encPass the actual SSHA or SHA encoded password
		 * @param rawPass unencoded password to be verified.
		 * @param salt ignored. If the format is SSHA the salt bytes will be extracted from
		 * the encoded password.
		 *
		 * @return true if they match (independent of the case of the prefix).
		 */
		public boolean isPasswordValid(final String encPass, final String rawPass, Object salt) {
			String prefix = extractPrefix(encPass);

			if (prefix == null) {
				return encPass.equals(rawPass);
			}

			if (prefix.equals(SSHA_PREFIX) || prefix.equals(SSHA_PREFIX_LC)) {
				salt = extractSalt(encPass);
			}
			else if (!prefix.equals(SHA_PREFIX) && !prefix.equals(SHA_PREFIX_LC)) {
				throw new IllegalArgumentException("Unsupported password prefix '" + prefix
						+ "'");
			}
			else {
				// Standard SHA
				salt = null;
			}

			int startOfHash = prefix.length();

			String encodedRawPass = encodePassword(rawPass, salt).substring(startOfHash);

			return equals(encodedRawPass, encPass.substring(startOfHash));
		}

		/**
		 * Returns the hash prefix or null if there isn't one.
		 */
		private String extractPrefix(String encPass) {
			if (!encPass.startsWith("{")) {
				return null;
			}

			int secondBrace = encPass.lastIndexOf('}');

			if (secondBrace < 0) {
				throw new IllegalArgumentException(
						"Couldn't find closing brace for SHA prefix");
			}

			return encPass.substring(0, secondBrace + 1);
		}

		public void setForceLowerCasePrefix(boolean forceLowerCasePrefix) {
			this.forceLowerCasePrefix = forceLowerCasePrefix;
		}

		static boolean equals(String expected, String actual) {
			byte[] expectedBytes = bytesUtf8(expected);
			byte[] actualBytes = bytesUtf8(actual);
			int expectedLength = expectedBytes == null ? -1 : expectedBytes.length;
			int actualLength = actualBytes == null ? -1 : actualBytes.length;

			int result = expectedLength == actualLength ? 0 : 1;
			for (int i = 0; i < actualLength; i++) {
				byte expectedByte = expectedLength <= 0 ? 0 : expectedBytes[i % expectedLength];
				byte actualByte = actualBytes[i % actualLength];
				result |= expectedByte ^ actualByte;
			}
			return result == 0;
		}

		private static byte[] bytesUtf8(String s) {
			if (s == null) {
				return null;
			}

			return Utf8.encode(s); // need to check if Utf8.encode() runs in constant time (probably not). This may leak length of string.
		}
	}
}
