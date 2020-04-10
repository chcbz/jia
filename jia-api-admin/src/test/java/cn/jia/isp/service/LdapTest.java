package cn.jia.isp.service;

import cn.jia.isp.entity.LdapAccountDTO;
import org.junit.Test;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author chcbz
 */
public class LdapTest {

    @Test
    public void conn () {
        LdapContextSource contextSource = new LdapContextSource();
        Map<String, Object> config = new HashMap<>();

        contextSource.setUrl("ldap://119.29.114.244:389");
        contextSource.setBase("dc=wydiy,dc=com");
        contextSource.setUserDn("cn=root,dc=wydiy,dc=com");
        contextSource.setPassword("secret");

        //  解决 乱码 的关键一句
        config.put("java.naming.ldap.attributes.binary", "objectGUID");

        contextSource.setPooled(true);
        contextSource.setBaseEnvironmentProperties(config);
        contextSource.afterPropertiesSet();

        LdapTemplate ldapTemplate = new LdapTemplate(contextSource);
        List<LdapAccountDTO> account = ldapTemplate.findAll(LdapAccountDTO.class);
        System.out.println(account.size());
    }
}
