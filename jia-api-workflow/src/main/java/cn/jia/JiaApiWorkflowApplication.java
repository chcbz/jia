package cn.jia;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.client.SpringCloudApplication;
import org.springframework.cloud.netflix.feign.EnableFeignClients;

@SpringCloudApplication
@EnableCaching
@ServletComponentScan
@EnableFeignClients
@MapperScan({"cn.jia.*.dao"})
public class JiaApiWorkflowApplication {

	public static void main(String[] args) {
		SpringApplication.run(JiaApiWorkflowApplication.class, args);
	}
	
}
