package cn.jia.test;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableCaching
@ServletComponentScan
@EnableTransactionManagement
@EnableScheduling
@EnableAsync
@EnableAutoConfiguration
@MapperScan({"cn.jia.*.dao"})
public class JiaTestApplication {

	public static void main(String[] args) {
		SpringApplication.run(JiaTestApplication.class, args);
	}
}
