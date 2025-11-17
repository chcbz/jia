package cn.jia.core.common;

import cn.jia.core.context.EsContext;
import cn.jia.core.util.StringUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Map;

public class EsSecurityHandler {
	private static final Logger logger = LoggerFactory.getLogger(EsSecurityHandler.class);

	// Jwt相关常量
	private static final String JWT_AUTHENTICATION_TOKEN_CLASS = "org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken";
	
	// Jwt类是否存在标识
	private static volatile Boolean isJwtAvailable = null;
	
	// 检查Jwt类是否可用
	private static boolean checkJwtAvailable() {
		if (isJwtAvailable == null) {
			synchronized (EsSecurityHandler.class) {
				if (isJwtAvailable == null) {
					try {
						Class.forName(JWT_AUTHENTICATION_TOKEN_CLASS);
						isJwtAvailable = true;
					} catch (ClassNotFoundException e) {
						logger.warn("Jwt classes not found, Jwt support disabled");
						isJwtAvailable = false;
					}
				}
			}
		}
		return isJwtAvailable;
	}
	
	public static String username() {
    	Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    	if(authentication != null && authentication.getPrincipal() instanceof UserDetails) {
    		return ((UserDetails)authentication.getPrincipal()).getUsername();
    	} else if (checkJwtAvailable() && isJwtAuthenticationToken(authentication)) {
			return getJwtPrincipalName(authentication);
    	}
    	return null;
    }
	
	/**
	 * 从Authentication对象中获取客户端ID
	 * @param authentication 认证对象
	 * @return 客户端ID，如果无法获取则返回null
	 */
	public static String getClientIdFromAuthentication(Authentication authentication) {
		if (authentication == null) {
			return null;
		}
		
		// 首先尝试从用户名获取clientId
		String username = null;
		if (authentication.getPrincipal() instanceof UserDetails) {
			username = ((UserDetails) authentication.getPrincipal()).getUsername();
		} else if (checkJwtAvailable() && isJwtAuthenticationToken(authentication)) {
			username = getJwtPrincipalName(authentication);
		} else if (authentication.getPrincipal() instanceof String) {
			username = (String) authentication.getPrincipal();
		}
		
		if (username != null) {
			// 直接返回用户名作为clientId
			return username;
		}
		
		// 如果无法从用户名获取，尝试直接从Jwt认证信息中获取
		if (checkJwtAvailable() && isJwtAuthenticationToken(authentication)) {
			return getJwtClientId(authentication);
		}
		
		return null;
	}
	
	/**
	 * 检查认证对象是否为JwtAuthenticationToken实例
	 */
	private static boolean isJwtAuthenticationToken(Authentication authentication) {
		if (authentication == null) return false;
		try {
			Class<?> jwtTokenClass = Class.forName(JWT_AUTHENTICATION_TOKEN_CLASS);
			return jwtTokenClass.isInstance(authentication);
		} catch (ClassNotFoundException e) {
			return false;
		}
	}
	
	/**
	 * 获取Jwt认证主体名称
	 */
	private static String getJwtPrincipalName(Authentication authentication) {
		try {
			// JwtAuthenticationToken可以直接获取Name
			return authentication.getName();
		} catch (Exception e) {
			logger.error("Error getting Jwt principal name", e);
		}
		return null;
	}
	
	/**
	 * 获取Jwt客户端ID
	 */
	private static String getJwtClientId(Authentication authentication) {
		try {
			// 通过反射获取Jwt并从中提取client_id
			Object jwt = authentication.getClass().getMethod("getToken").invoke(authentication);
			if (jwt != null) {
				// 获取claims
				@SuppressWarnings("unchecked")
				Map<String, Object> claims = (Map<String, Object>) jwt.getClass().getMethod("getClaims").invoke(jwt);
				if (claims != null) {
					// 尝试获取client_id
					Object clientId = claims.get("client_id");
					if (clientId != null) {
						return clientId.toString();
					}
					// 尝试获取clientId
					clientId = claims.get("clientId");
					if (clientId != null) {
						return clientId.toString();
					}
					// 尝试获取aud作为备选
					Object aud = claims.get("aud");
					if (aud != null) {
						return aud.toString();
					}
				}
			}
		} catch (Exception e) {
			logger.error("Error getting Jwt client ID", e);
		}
		return null;
	}
	
	/**
     * 获取登录客户ID
     * @return 客户端ID
     */
	public static String clientId() {
    	Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    	String username = usernameFromAuthentication(authentication);
    	if(username != null) {
    		// 直接返回用户名作为clientId
    		return username;
    	} else if (checkJwtAvailable() && isJwtAuthenticationToken(authentication)) {
    		return getJwtClientId(authentication);
    	}
    	return null;
    }
	
	/**
	 * 从认证对象中获取用户名
	 * @param authentication 认证对象
	 * @return 用户名，如果无法获取则返回null
	 */
	private static String usernameFromAuthentication(Authentication authentication) {
		if(authentication != null && authentication.getPrincipal() instanceof UserDetails) {
    		return ((UserDetails)authentication.getPrincipal()).getUsername();
    	} else if (checkJwtAvailable() && isJwtAuthenticationToken(authentication)) {
			return getJwtPrincipalName(authentication);
    	}
		return null;
	}
	
	/**
	 * 根据token获取客户端ID
	 * @param token token
	 * @return 客户端ID
	 */
	public static String clientId(String token) {
		if (token == null || token.isEmpty()) {
			return null;
		}
		return null;
	}
    
    /**
     * 获取Jia平台Token
     * @return token
     */
    public static String jiaToken() {
    	// 直接返回null，因为移除了RestTemplate逻辑
		// 如果需要获取Jia平台Token，应在此处实现替代逻辑
		return null;
    }
    
    /**
     * 检查是否有ClientId
     * @param request HTTP请求
     * @throws AccessDeniedException 异常
     */
    public static String checkClientId(HttpServletRequest request) throws AccessDeniedException {
    	String clientId = clientId(request);
    	if(StringUtil.isEmpty(clientId)) {
    		throw new AccessDeniedException("Unable to get clientId");
    	}
    	return clientId;
    }
    
    /**
     * 获取ClientId
     * @param request HTTP请求
     * @return 客户端ID
     */
	public static String clientId(HttpServletRequest request) {
    	try {
    		String clientId = clientId();
    		if(StringUtil.isNotEmpty(clientId)) {
        		return clientId;
        	} else {
				if (request == null) {
					return null;
				}
				
        		String appcn = StringUtil.valueOf(request.getParameter("appcn"));
        		if(StringUtil.isEmpty(appcn)) {
        			String auth = request.getHeader("Authorization");
        			if(StringUtil.isNotEmpty(auth) && auth.startsWith("APPCN ")) {
        				appcn = auth.substring(6);
        			}
        		}
				return null;
        	}
    	} catch (Exception e) {
    		logger.error("clientId", e);
    		return null;
    	}
    }

	/**
	 * 根据token获取当前用户上下文
	 *
	 * @param token token
	 * @return 用户上下文
	 */
	public static EsContext currentContext(String token) {
		return new EsContext();
	}
}