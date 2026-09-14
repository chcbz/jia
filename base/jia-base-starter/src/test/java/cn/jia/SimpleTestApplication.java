package cn.jia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SimpleTestApplication {
    public static void main(String[] args) {
        try {
            SpringApplication app = new SpringApplication(SimpleTestApplication.class);
            app.setAdditionalProfiles("test");
            app.run(args);
            System.out.println("SUCCESS: Application started without NoSuchMethodError!");
            System.exit(0);
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getClass().getName());
            System.err.println("Message: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}