package cn.jia.user.entity;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;

/**
 * 自定义用户详情类，扩展Spring Security的UserDetails接口
 * 添加了jiacn等额外的用户属性，用于在认证过程中携带更多用户信息
 */
public class CustomUserDetails extends User {
    private final String jiacn;

    /**
     * 构造函数
     *
     * @param username 用户名
     * @param password 密码
     * @param jiacn Jia账号
     * @param authorities 权限集合
     */
    public CustomUserDetails(String jiacn, String username, String password,
                             Collection<? extends GrantedAuthority> authorities) {
        super(username, password, authorities);
        this.jiacn = jiacn;
    }

    public String getJiacn() {
        return jiacn;
    }
}