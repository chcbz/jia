package cn.jia.oauth;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.security.authentication.encoding.LdapShaPasswordEncoder;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
//@SpringBootTest
public class JiaApplicationTests {
	@Test
    public void ldapPassword(){
        LdapShaPasswordEncoder encoder = new LdapShaPasswordEncoder();
        System.out.println(encoder.encodePassword("123", null));
    }
}
