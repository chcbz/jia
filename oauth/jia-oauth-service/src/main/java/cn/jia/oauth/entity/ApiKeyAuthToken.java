package cn.jia.oauth.entity;

import lombok.Getter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * API Key认证令牌
 * 用于封装API Key认证过程中的身份信息
 */
public class ApiKeyAuthToken extends AbstractAuthenticationToken {

    /**
     *  获取API密钥
     */
    @Getter
    private final String apiKey;
    private Object principal;

    /**
     * 创建未认证的API Key令牌
     *
     * @param apiKey API密钥
     */
    public ApiKeyAuthToken(String apiKey) {
        super((Collection<? extends GrantedAuthority>)null);
        this.apiKey = apiKey;
        this.setAuthenticated(false);
    }

    /**
     * 创建已认证的API Key令牌
     *
     * @param principal   主体信息（客户端ID）
     * @param authorities 授权信息
     */
    public ApiKeyAuthToken(Object principal, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        this.apiKey = null;
        super.setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return this.principal;
    }

    @Override
    public void setAuthenticated(boolean isAuthenticated) throws IllegalArgumentException {
        if (isAuthenticated) {
            throw new IllegalArgumentException("不能直接设置为已认证，请使用构造函数");
        }
        super.setAuthenticated(false);
    }
}
