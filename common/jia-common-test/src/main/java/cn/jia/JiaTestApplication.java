package cn.jia;

import cn.jia.test.RedisServerLoader;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @author chc
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
@MapperScan({"cn.jia.*.mapper"})
public class JiaTestApplication {
	public JiaTestApplication(RedisServerLoader redisServerLoader) {
	}

	public static void main(String[] args) {
		SpringApplication.run(JiaTestApplication.class, args);
	}
}
