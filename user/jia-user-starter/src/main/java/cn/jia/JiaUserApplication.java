package cn.jia;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement
@EnableAsync
@MapperScan({"cn.jia.*.mapper"})
public class JiaUserApplication {

	public static void main(String[] args) {
		SpringApplication.run(JiaUserApplication.class, args);
	}
}
