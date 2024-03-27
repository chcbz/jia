package cn.jia;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@ServletComponentScan
@EnableTransactionManagement
@EnableAsync
@MapperScan({"cn.jia.*.mapper"})
public class JiaApplication {

	public static void main(String[] args) {
		SpringApplication.run(JiaApplication.class, args);
	}
}
