package cn.jia.ai;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class JiaMcpServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(JiaMcpServerApplication.class, args);
	}

	@Bean
	public ToolCallbackProvider weatherTools(DatabaseTool databaseTool) {
		return MethodToolCallbackProvider.builder().toolObjects(databaseTool).build();
	}
}
