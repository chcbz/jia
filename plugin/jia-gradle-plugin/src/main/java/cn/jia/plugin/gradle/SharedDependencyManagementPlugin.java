package cn.jia.plugin.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.initialization.Settings;

public class SharedDependencyManagementPlugin implements Plugin<Settings> {
    @Override
    public void apply(Settings settings) {
        settings.dependencyResolutionManagement(drm ->
                drm.versionCatalogs(catalogs ->
                        catalogs.create("libs", catalog -> {
                            // === 核心框架依赖 ===
                            catalog.version("spring", "7.0.2");
                            catalog.version("spring-boot", "4.0.1");
                            catalog.version("spring-cloud", "2025.1.1");
                            catalog.version("spring-ai", "2.0.0-M2");
                            catalog.version("spring-security", "7.0.2");
                            catalog.version("spring-security-oauth2", "7.0.2");

                            // === Spring社区相关 ===
                            catalog.version("spring-ai-agent-utils", "0.4.2");

                            // === 数据库相关 ===
                            catalog.version("mybatis-plus", "3.5.16");
                            catalog.version("mybatis", "3.5.19");
                            catalog.version("pagehelper", "6.1.1");
                            catalog.version("mysql-connector-j", "8.3.0");
                            catalog.version("elasticsearch", "9.2.2");
                            catalog.version("jedis", "4.4.1");
                            catalog.version("h2", "2.3.232");
                            catalog.version("druid-core", "1.2.27");

                            // === Spring Boot Starter相关 ===
                            catalog.version("spring-boot-pagehelper", "2.1.1");
                            catalog.version("spring-boot-redisson", "4.2.0");
                            catalog.version("spring-boot-druid", "1.2.27");
                            catalog.version("spring-boot-swagger", "3.0.1");
                            catalog.version("spring-boot-mybatis", "4.0.1");
                            catalog.version("spring-boot-jasypt", "4.0.4");

                            // === 工具类库 ===
                            catalog.version("lombok", "1.18.42");
                            catalog.version("mapstruct", "1.6.3");
                            catalog.version("jackson-databind", "3.0.3");
                            catalog.version("jackson-annotations", "2.20");
                            catalog.version("guava", "33.4.0-jre");
                            catalog.version("commons-lang3", "3.17.0");
                            catalog.version("commons-io", "2.18.0");
                            catalog.version("commons-codec", "1.18.0");
                            catalog.version("commons-collections", "3.2.2");
                            catalog.version("commons-fileupload", "1.5");
                            catalog.version("commons-net", "3.11.1");
                            catalog.version("commons-logging", "1.3.5");
                            catalog.version("httpclient", "4.5.14");

                            // === 日志相关 ===
                            catalog.version("slf4j-api", "2.0.6");
                            catalog.version("slf4j-log4j12", "1.7.5");
                            catalog.version("log4j", "1.2.17");

                            // === Servlet和注入相关 ===
                            catalog.version("javax-servlet-api", "3.1.0");
                            catalog.version("jakarta-servlet-api", "6.1.0");
                            catalog.version("javax-inject", "1");
                            catalog.version("jakarta-inject-api", "2.0.1");
                            catalog.version("jakarta-annotation-api", "3.0.0");

                            // === XML和其他基础库 ===
                            catalog.version("dom4j", "2.2.0");
                            catalog.version("json", "20230227");

                            // === 微信相关 ===
                            catalog.version("weixin-java", "4.6.0");

                            // === 邮件和二维码 ===
                            catalog.version("mail", "1.4");
                            catalog.version("zxing-core", "3.4.0");
                            catalog.version("zxing-javase", "3.4.0");

                            // === 图像处理 ===
                            catalog.version("jai-imageio", "1.1");
                            catalog.version("opencv", "4.5.5");
                            catalog.version("thumbnailator", "0.4.21");
                            catalog.version("html2image", "0.1.0");

                            // === 文档处理 ===
                            catalog.version("easypoi-base", "3.0.3");
                            catalog.version("easypoi-annotation", "3.0.3");
                            catalog.version("pdf2dom", "1.7");
                            catalog.version("pdfbox", "2.0.15");
                            catalog.version("pdfbox-tools", "2.0.12");
                            catalog.version("poi-scratchpad", "3.14");
                            catalog.version("poi-ooxml", "3.14");
                            catalog.version("xdocreport", "1.0.6");
                            catalog.version("poi-ooxml-full", "5.4.0");

                            // === 规则引擎 ===
                            catalog.version("mvel", "2.5.2.Final");
                            catalog.version("jodd", "3.3.8");

                            // === 工作流引擎 ===
                            catalog.version("camunda", "7.24.0");

                            // === SSH和云服务 ===
                            catalog.version("ganymed-ssh2", "262");
                            catalog.version("aliyun-java-sdk-core", "4.5.16");
                            catalog.version("aliyun-java-sdk-alidns", "2.0.10");
                            catalog.version("aliyun-java-sdk-dysmsapi", "2.1.0");
                            catalog.version("stripe-java", "4.4.0");

                            // === Spring扩展组件 ===
                            catalog.version("spring-ldap", "4.0.1");
                            catalog.version("spring-rabbit", "4.0.1");
                            catalog.version("swagger-annotations", "2.2.10");

                            // === 测试相关 ===
                            catalog.version("junit-platform", "6.0.2");
                            catalog.version("junit-jupiter", "6.0.2");
                            catalog.version("dbunit", "3.0.0");
                            catalog.version("mockito-core", "5.20.0");
                            catalog.version("redis-server", "0.3.3");
                            catalog.version("testcontainers-rabbitmq", "1.20.6");
                            catalog.version("testcontainers-junit", "1.20.6");
                            catalog.version("testable-all", "0.7.9");
                            catalog.version("testable-processor", "0.7.9");

                            catalog.library("pagehelper-core", "com.github.pagehelper", "pagehelper").versionRef("pagehelper");
                            catalog.library("pagehelper-spring-boot-starter", "com.github.pagehelper", "pagehelper-spring-boot-starter").versionRef("spring-boot-pagehelper");
                            catalog.library("mybatis-core", "org.mybatis", "mybatis").versionRef("mybatis");
                            catalog.library("mybatis-plus-core", "com.baomidou", "mybatis-plus").versionRef("mybatis-plus");
                            catalog.library("mybatis-plus-annotation", "com.baomidou", "mybatis-plus-annotation").versionRef("mybatis-plus");
                            catalog.library("mybatis-plus-generator", "com.baomidou", "mybatis-plus-generator").versionRef("mybatis-plus");
                            catalog.library("mybatis-plus-boot-starter", "com.baomidou", "mybatis-plus-spring-boot4-starter").versionRef("mybatis-plus");
                            catalog.library("swagger.annotations", "io.swagger.core.v3", "swagger-annotations").versionRef("swagger-annotations");
                            catalog.library("lombok", "org.projectlombok", "lombok").versionRef("lombok");
                            catalog.library("spring.beans", "org.springframework", "spring-beans").versionRef("spring");
                            catalog.library("spring.context", "org.springframework", "spring-context").versionRef("spring");
                            catalog.library("spring.web", "org.springframework", "spring-web").versionRef("spring");
                            catalog.library("spring.webmvc", "org.springframework", "spring-webmvc").versionRef("spring");
                            catalog.library("spring.tx", "org.springframework", "spring-tx").versionRef("spring");
                            catalog.library("spring.jdbc", "org.springframework", "spring-jdbc").versionRef("spring");
                            catalog.library("spring.aspects", "org.springframework", "spring-aspects").versionRef("spring");
                            catalog.library("spring.websocket", "org.springframework", "spring-websocket").versionRef("spring");
                            catalog.library("spring.redis", "org.springframework.data", "spring-data-redis").versionRef("spring-boot");
                            catalog.library("spring.ldap", "org.springframework.ldap", "spring-ldap-core").versionRef("spring-ldap");
                            catalog.library("spring.rabbit", "org.springframework.amqp", "spring-rabbit").versionRef("spring-rabbit");
                            catalog.library("spring.security.core", "org.springframework.security", "spring-security-core").versionRef("spring-security");
                            catalog.library("spring.security.config", "org.springframework.security", "spring-security-config").versionRef("spring-security");
                            catalog.library("spring.security.web", "org.springframework.security", "spring-security-web").versionRef("spring-security");
                            catalog.library("spring.security.oauth2.client", "org.springframework.security", "spring-security-oauth2-client").versionRef("spring-security");
                            catalog.library("spring-security-oauth2-resource-server", "org.springframework.security", "spring-security-oauth2-resource-server").versionRef("spring-security");
                            catalog.library("spring-security-oauth2-jose", "org.springframework.security", "spring-security-oauth2-jose").versionRef("spring-security");
                            catalog.library("spring-security-oauth2-authorization-server", "org.springframework.security", "spring-security-oauth2-authorization-server").versionRef("spring-security-oauth2");
                            catalog.library("spring-boot-configuration-processor", "org.springframework.boot", "spring-boot-configuration-processor").versionRef("spring-boot");
                            catalog.library("spring-boot-autoconfigure", "org.springframework.boot", "spring-boot-autoconfigure").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-web", "org.springframework.boot", "spring-boot-starter-web").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-webflux", "org.springframework.boot", "spring-boot-starter-webflux").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-validation", "org.springframework.boot", "spring-boot-starter-validation").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-tomcat", "org.springframework.boot", "spring-boot-starter-tomcat").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-mybatis", "org.mybatis.spring.boot", "mybatis-spring-boot-starter").versionRef("spring-boot-mybatis");
                            catalog.library("spring-boot-starter-websocket", "org.springframework.boot", "spring-boot-starter-websocket").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-security", "org.springframework.boot", "spring-boot-starter-security").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-oauth2-authorization-server", "org.springframework.boot", "spring-boot-starter-oauth2-authorization-server").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-oauth2-resource-server", "org.springframework.boot", "spring-boot-starter-oauth2-resource-server").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-oauth2-client", "org.springframework.boot", "spring-boot-starter-oauth2-client").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-data-jdbc", "org.springframework.boot", "spring-boot-starter-data-jdbc").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-cache", "org.springframework.boot", "spring-boot-starter-cache").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-data-redis", "org.springframework.boot", "spring-boot-starter-data-redis").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-redisson", "org.redisson", "redisson-spring-boot-starter").versionRef("spring-boot-redisson");
                            catalog.library("spring-boot-starter-amqp", "org.springframework.boot", "spring-boot-starter-amqp").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-data-ldap", "org.springframework.boot", "spring-boot-starter-data-ldap").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-elasticsearch", "org.springframework.boot", "spring-boot-starter-elasticsearch").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-druid", "com.alibaba", "druid-spring-boot-starter").versionRef("spring-boot-druid");
                            catalog.library("spring-boot-starter-thymeleaf", "org.springframework.boot", "spring-boot-starter-thymeleaf").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-freemarker", "org.springframework.boot", "spring-boot-starter-freemarker").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-swagger", "org.springdoc", "springdoc-openapi-starter-webmvc-ui").versionRef("spring-boot-swagger");
                            catalog.library("spring-boot-starter-test", "org.springframework.boot", "spring-boot-starter-test").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-jasypt", "com.github.ulisesbocchio", "jasypt-spring-boot-starter").versionRef("spring-boot-jasypt");
                            catalog.library("spring-boot-starter-actuator", "org.springframework.boot", "spring-boot-starter-actuator").versionRef("spring-boot");
                            catalog.library("spring-boot-properties-migrator", "org.springframework.boot", "spring-boot-properties-migrator").versionRef("spring-boot");
                            catalog.library("spring-cloud-config-server", "org.springframework.cloud", "spring-cloud-config-server").versionRef("spring-cloud");
                            catalog.library("spring-cloud-starter-netflix-eureka-server", "org.springframework.cloud", "spring-cloud-starter-netflix-eureka-server").versionRef("spring-cloud");
                            catalog.library("spring-cloud-starter-zuul", "org.springframework.cloud", "spring-cloud-starter-zuul").versionRef("spring-cloud");
                            catalog.library("spring-cloud-starter-security", "org.springframework.cloud", "spring-cloud-starter-security").versionRef("spring-cloud");
                            catalog.library("spring-cloud-starter-oauth2", "org.springframework.cloud", "spring-cloud-starter-oauth2").versionRef("spring-cloud");
                            catalog.library("spring-ai-starter-mcp-client", "org.springframework.ai", "spring-ai-starter-mcp-client").versionRef("spring-ai");
                            catalog.library("spring-ai-starter-mcp-server", "org.springframework.ai", "spring-ai-starter-mcp-server").versionRef("spring-ai");
                            catalog.library("spring-ai-starter-model-zhipuai", "org.springframework.ai", "spring-ai-starter-model-zhipuai").versionRef("spring-ai");
                            catalog.library("spring-ai-starter-model-deepseek", "org.springframework.ai", "spring-ai-starter-model-deepseek").versionRef("spring-ai");
                            catalog.library("spring-ai-starter-model-ollama", "org.springframework.ai", "spring-ai-starter-model-ollama").versionRef("spring-ai");
                            catalog.library("spring-ai-starter-vector-store-elasticsearch", "org.springframework.ai", "spring-ai-starter-vector-store-elasticsearch").versionRef("spring-ai");
                            catalog.library("spring-ai-starter-vector-store-redis", "org.springframework.ai", "spring-ai-starter-vector-store-redis").versionRef("spring-ai");
                            catalog.library("spring-ai-advisors-vector-store", "org.springframework.ai", "spring-ai-advisors-vector-store").versionRef("spring-ai");
                            catalog.library("spring-ai-agent-utils", "org.springaicommunity", "spring-ai-agent-utils").versionRef("spring-ai-agent-utils");
                            catalog.library("jackson-databind", "tools.jackson.core", "jackson-databind").versionRef("jackson-databind");
                            catalog.library("jackson-annotations", "com.fasterxml.jackson.core", "jackson-annotations").versionRef("jackson-annotations");
                            catalog.library("json", "org.json", "json").versionRef("json");
                            catalog.library("log4j", "log4j", "log4j").versionRef("log4j");
                            catalog.library("slf4j.api", "org.slf4j", "slf4j-api").versionRef("slf4j-api");
                            catalog.library("slf4j-log4j12", "org.slf4j", "slf4j-log4j12").versionRef("slf4j-log4j12");
                            catalog.library("dom4j", "org.dom4j", "dom4j").versionRef("dom4j");
                            catalog.library("javax.inject", "javax.inject", "javax.inject").versionRef("javax-inject");
                            catalog.library("javax.servlet-api", "javax.servlet", "javax.servlet-api").versionRef("javax-servlet-api");
                            catalog.library("jakarta.inject.api", "jakarta.inject", "jakarta.inject-api").versionRef("jakarta-inject-api");
                            catalog.library("jakarta.annotation.api", "jakarta.annotation", "jakarta.annotation-api").versionRef("jakarta-annotation-api");
                            catalog.library("jakarta.servlet.api", "jakarta.servlet", "jakarta.servlet-api").versionRef("jakarta-servlet-api");
                            catalog.library("commons-fileupload", "commons-fileupload", "commons-fileupload").versionRef("commons-fileupload");
                            catalog.library("commons-codec", "commons-codec", "commons-codec").versionRef("commons-codec");
                            catalog.library("commons-net", "commons-net", "commons-net").versionRef("commons-net");
                            catalog.library("commons-logging", "commons-logging", "commons-logging").versionRef("commons-logging");
                            catalog.library("commons-collections", "commons-collections", "commons-collections").versionRef("commons-collections");
                            catalog.library("commons-io", "commons-io", "commons-io").versionRef("commons-io");
                            catalog.library("commons-lang3", "org.apache.commons", "commons-lang3").versionRef("commons-lang3");
                            catalog.library("guava", "com.google.guava", "guava").versionRef("guava");
                            catalog.library("httpclient", "org.apache.httpcomponents", "httpclient").versionRef("httpclient");
                            catalog.library("mysql", "com.mysql", "mysql-connector-j").versionRef("mysql-connector-j");
                            catalog.library("elasticsearch", "co.elastic.clients", "elasticsearch-java").versionRef("elasticsearch");
                            catalog.library("jedis", "redis.clients", "jedis").versionRef("jedis");
                            catalog.library("camunda", "org.camunda.bpm", "camunda-engine").versionRef("camunda");
                            catalog.library("jodd", "org.jodd", "jodd").versionRef("jodd");
                            catalog.library("zxing-core", "com.google.zxing", "core").versionRef("zxing-core");
                            catalog.library("zxing-javase", "com.google.zxing", "javase").versionRef("zxing-javase");
                            catalog.library("mail", "javax.mail", "mail").versionRef("mail");
                            catalog.library("jai_imageio", "com.sun.media", "jai_imageio").versionRef("jai-imageio");
                            catalog.library("opencv", "org.opencv", "opencv").versionRef("opencv");
                            catalog.library("easypoi-base", "cn.afterturn", "easypoi-base").versionRef("easypoi-base");
                            catalog.library("easypoi-annotation", "cn.afterturn", "easypoi-annotation").versionRef("easypoi-annotation");
                            catalog.library("html2image", "com.github.xuwei-k", "html2image").versionRef("html2image");
                            catalog.library("mvel", "org.mvel", "mvel2").versionRef("mvel");
                            // 图片压缩
                            catalog.library("thumbnailator", "net.coobird", "thumbnailator").versionRef("thumbnailator");
                            catalog.library("ganymed-ssh2", "ch.ethz.ganymed", "ganymed-ssh2").versionRef("ganymed-ssh2");
                            catalog.library("aliyun-java-sdk-core", "com.aliyun", "aliyun-java-sdk-core").versionRef("aliyun-java-sdk-core");
                            catalog.library("aliyun-java-sdk-alidns", "com.aliyun", "aliyun-java-sdk-alidns").versionRef("aliyun-java-sdk-alidns");
                            catalog.library("aliyun-java-sdk-dysmsapi", "com.aliyun", "aliyun-java-sdk-dysmsapi").versionRef("aliyun-java-sdk-dysmsapi");
                            catalog.library("stripe-java", "com.stripe", "stripe-java").versionRef("stripe-java");
                            catalog.library("pdf2dom", "net.sf.cssbox", "pdf2dom").versionRef("pdf2dom");
                            catalog.library("pdfbox-core", "org.apache.pdfbox", "pdfbox").versionRef("pdfbox");
                            catalog.library("pdfbox-tools", "org.apache.pdfbox", "pdfbox-tools").versionRef("pdfbox-tools");
                            catalog.library("poi-scratchpad", "org.apache.poi", "poi-scratchpad").versionRef("poi-scratchpad");
                            catalog.library("poi-ooxml-core", "org.apache.poi", "poi-ooxml").versionRef("poi-ooxml");
                            catalog.library("xdocreport", "fr.opensagres.xdocreport", "xdocreport").versionRef("xdocreport");
                            catalog.library("poi-ooxml-full", "org.apache.poi", "poi-ooxml-full").versionRef("poi-ooxml-full");
                            catalog.library("junit-jupiter", "org.junit.jupiter", "junit-jupiter").versionRef("junit-jupiter");
                            catalog.library("junit-platform-engine", "org.junit.platform", "junit-platform-engine").versionRef("junit-platform");
                            catalog.library("junit-platform-launcher", "org.junit.platform", "junit-platform-launcher").versionRef("junit-platform");
                            catalog.library("junit-platform-commons", "org.junit.platform", "junit-platform-commons").versionRef("junit-platform");
                            catalog.library("junit-jupiter-api", "org.junit.jupiter", "junit-jupiter-api").versionRef("junit-jupiter");
                            catalog.library("junit-jupiter-engine", "org.junit.jupiter", "junit-jupiter-engine").versionRef("junit-jupiter");
                            catalog.library("junit-jupiter-params", "org.junit.jupiter", "junit-jupiter-params").versionRef("junit-jupiter");
                            catalog.library("h2", "com.h2database", "h2").versionRef("h2");
                            catalog.library("druid", "com.alibaba", "druid").versionRef("druid-core");
                            catalog.library("dbunit", "org.dbunit", "dbunit").versionRef("dbunit");
                            catalog.library("mockito-core", "org.mockito", "mockito-core").versionRef("mockito-core");
                            catalog.library("redis-server", "com.github.microwww", "redis-server").versionRef("redis-server");
                            catalog.library("testcontainers-rabbitmq", "org.testcontainers", "rabbitmq").versionRef("testcontainers-rabbitmq");
                            catalog.library("testcontainers-junit", "org.testcontainers", "junit-jupiter").versionRef("testcontainers-junit");
                            catalog.library("testable-all", "com.alibaba.testable", "testable-all").versionRef("testable-all");
                            catalog.library("testable-processor", "com.alibaba.testable", "testable-processor").versionRef("testable-processor");
                            catalog.library("weixin-java-pay", "com.github.binarywang", "weixin-java-pay").versionRef("weixin-java");
                            catalog.library("weixin-java-mp", "com.github.binarywang", "weixin-java-mp").versionRef("weixin-java");
                            catalog.library("mapstruct.core", "org.mapstruct", "mapstruct").versionRef("mapstruct");
                            catalog.library("mapstruct.processor", "org.mapstruct", "mapstruct-processor").versionRef("mapstruct");
                        })));
    }
}