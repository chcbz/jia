package cn.jia.user;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import cn.jia.user.service.OrgService;

@RunWith(SpringRunner.class)
@SpringBootTest
public class JiaApiUserApplicationTests {
	
	@Autowired
	private OrgService orgService;

	@Test
	public void contextLoads() throws Exception {
		orgService.findDirector(6, "Vehicle Management Approver");
	}

}
