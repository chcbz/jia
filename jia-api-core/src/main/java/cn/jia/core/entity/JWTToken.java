package cn.jia.core.entity;

import org.apache.shiro.authc.AuthenticationToken;

public class JWTToken implements AuthenticationToken {
	private static final long serialVersionUID = 502292969299058479L;
	// 密钥
	private String token;

	public JWTToken(String token) {
		this.token = token;
	}

	@Override
	public Object getPrincipal() {
		return token;
	}

	@Override
	public Object getCredentials() {
		return token;
	}
}
