package cn.jia.base.config;

import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 处理AJAX请求跨域的问题
 *
 * @author chcbz
 * @since 2018年4月24日 上午11:57:33
 */
@Configuration
public class CorsConfig {
    private CorsConfiguration buildConfig() {
        CorsConfiguration corsConfiguration = new CorsConfiguration();
        corsConfiguration.addAllowedOrigin("*");
        corsConfiguration.addAllowedHeader("*");
        corsConfiguration.addAllowedMethod("*");
        corsConfiguration.setAllowCredentials(true);
//        corsConfiguration.addExposedHeader(HttpHeaderConStant.X_TOTAL_COUNT);
        return corsConfiguration;
    }

    public static void main(String[] args) {
        String originalText = "jia_dev";
        String password = "123456";

        StandardPBEStringEncryptor encryptor = new StandardPBEStringEncryptor();
        encryptor.setAlgorithm("PBEWithMD5AndDES");
        encryptor.setPassword(password);

        String encryptedText = encryptor.encrypt(originalText);
        System.out.println("Encrypted Text: " + encryptedText);
    }

    /**
     * 跨域过滤器
     *
     * @return 跨域过滤器
     */
    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", buildConfig()); // 4
        return new CorsFilter(source);
    }
}
