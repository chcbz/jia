package cn.jia.plugin.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.initialization.Settings;

public class SharedDependencyManagementPlugin implements Plugin<Settings> {
    @Override
    public void apply(Settings settings) {
        settings.dependencyResolutionManagement(drm ->
                drm.versionCatalogs(catalogs ->
                        catalogs.create("libs", catalog -> {
                            catalog.version("spring", "6.1.5");
                            catalog.version("spring-boot", "3.2.4");
                            catalog.version("spring-cloud", "Edgware.SR3");
                            catalog.version("spring-security", "6.2.3");
                            catalog.version("spring-security-oauth2", "1.2.3");
                            catalog.version("weixin-java", "4.5.6.B");
                            catalog.version("mybatis-plus", "3.5.5");
                            catalog.version("mapstruct", "1.5.5.Final");
                            catalog.version("spring-boot-pagehelper", "2.1.0");
                            catalog.version("spring-boot-redisson", "3.27.2");
                            catalog.version("spring-boot-druid", "1.2.22");
                            catalog.version("spring-boot-swagger", "4.5.0");
                            catalog.version("spring-boot-mybatis", "3.0.3");
                            catalog.version("spring-boot-jasypt", "3.0.5");

                            catalog.library("pagehelper-core", "com.github.pagehelper:pagehelper:5.3.2");
                            catalog.library("pagehelper-spring-boot-starter", "com.github.pagehelper", "pagehelper-spring-boot-starter").versionRef("spring-boot-pagehelper");
                            catalog.library("mybatis-core", "org.mybatis:mybatis:3.5.14");
                            catalog.library("mybatis-plus-core", "com.baomidou", "mybatis-plus").versionRef("mybatis-plus");
                            catalog.library("mybatis-plus-annotation", "com.baomidou", "mybatis-plus-annotation").versionRef("mybatis-plus");
                            catalog.library("mybatis-plus-generator", "com.baomidou", "mybatis-plus-generator").versionRef("mybatis-plus");
                            catalog.library("mybatis-plus-boot-starter", "com.baomidou", "mybatis-plus-boot-starter").versionRef("mybatis-plus");
                            catalog.library("swagger.annotations", "io.swagger.core.v3:swagger-annotations:2.2.10");
                            catalog.library("lombok", "org.projectlombok:lombok:1.18.28");
                            catalog.library("spring.beans", "org.springframework", "spring-beans").versionRef("spring");
                            catalog.library("spring.context", "org.springframework", "spring-context").versionRef("spring");
                            catalog.library("spring.web", "org.springframework", "spring-web").versionRef("spring");
                            catalog.library("spring.webmvc", "org.springframework", "spring-webmvc").versionRef("spring");
                            catalog.library("spring.tx", "org.springframework", "spring-tx").versionRef("spring");
                            catalog.library("spring.jdbc", "org.springframework", "spring-jdbc").versionRef("spring");
                            catalog.library("spring.aspects", "org.springframework", "spring-aspects").versionRef("spring");
                            catalog.library("spring.websocket", "org.springframework", "spring-websocket").versionRef("spring");
                            catalog.library("spring.ldap", "org.springframework.ldap", "spring-ldap-core").version("3.2.2");
                            catalog.library("spring.redis", "org.springframework.data", "spring-data-redis").version("3.1.0");
                            catalog.library("spring.elasticsearch", "org.springframework.data", "spring-data-elasticsearch").version("5.2.4");
                            catalog.library("spring.rabbit", "org.springframework.amqp", "spring-rabbit").version("3.1.3");
                            catalog.library("spring.security.core", "org.springframework.security", "spring-security-core").versionRef("spring-security");
                            catalog.library("spring.security.config", "org.springframework.security", "spring-security-config").versionRef("spring-security");
                            catalog.library("spring.security.web", "org.springframework.security", "spring-security-web").versionRef("spring-security");
                            catalog.library("spring.security.oauth2.client", "org.springframework.security", "spring-security-oauth2-client").versionRef("spring-security");
                            catalog.library("spring-security-oauth2-resource-server", "org.springframework.security", "spring-security-oauth2-resource-server").versionRef("spring-security");
                            catalog.library("spring-security-oauth2-authorization-server", "org.springframework.security", "spring-security-oauth2-authorization-server").versionRef("spring-security-oauth2");
                            catalog.library("spring-boot-configuration-processor", "org.springframework.boot", "spring-boot-configuration-processor").versionRef("spring-boot");
                            catalog.library("spring-boot-autoconfigure", "org.springframework.boot", "spring-boot-autoconfigure").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-web", "org.springframework.boot", "spring-boot-starter-web").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-validation", "org.springframework.boot", "spring-boot-starter-validation").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-undertow", "org.springframework.boot", "spring-boot-starter-undertow").versionRef("spring-boot");
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
                            catalog.library("spring-boot-starter-data-elasticsearch", "org.springframework.boot", "spring-boot-starter-data-elasticsearch").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-druid", "com.alibaba", "druid-spring-boot-starter").versionRef("spring-boot-druid");
                            catalog.library("spring-boot-starter-thymeleaf", "org.springframework.boot", "spring-boot-starter-thymeleaf").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-freemarker", "org.springframework.boot", "spring-boot-starter-freemarker").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-swagger", "com.github.xiaoymin", "knife4j-openapi3-jakarta-spring-boot-starter").versionRef("spring-boot-swagger");
                            catalog.library("spring-boot-starter-test", "org.springframework.boot", "spring-boot-starter-test").versionRef("spring-boot");
                            catalog.library("spring-boot-starter-jasypt", "com.github.ulisesbocchio", "jasypt-spring-boot-starter").versionRef("spring-boot-jasypt");
                            catalog.library("spring-boot-starter-actuator", "org.springframework.boot", "spring-boot-starter-actuator").versionRef("spring-boot");
                            catalog.library("spring-boot-properties-migrator", "org.springframework.boot", "spring-boot-properties-migrator").versionRef("spring-boot");
                            catalog.library("spring-cloud-config-server", "org.springframework.cloud", "spring-cloud-config-server").versionRef("spring-cloud");
                            catalog.library("spring-cloud-starter-netflix-eureka-server", "org.springframework.cloud", "spring-cloud-starter-netflix-eureka-server").versionRef("spring-cloud");
                            catalog.library("spring-cloud-starter-zuul", "org.springframework.cloud", "spring-cloud-starter-zuul").versionRef("spring-cloud");
                            catalog.library("spring-cloud-starter-security", "org.springframework.cloud", "spring-cloud-starter-security").versionRef("spring-cloud");
                            catalog.library("spring-cloud-starter-oauth2", "org.springframework.cloud", "spring-cloud-starter-oauth2").versionRef("spring-cloud");
                            catalog.library("spring-test-dbunit", "com.github.springtestdbunit:spring-test-dbunit:1.3.0");
                            catalog.library("jackson-core", "com.fasterxml.jackson.core:jackson-core:2.15.2");
                            catalog.library("jackson-annotations", "com.fasterxml.jackson.core:jackson-annotations:2.15.2");
                            catalog.library("json", "org.json:json:20230227");
                            catalog.library("log4j", "log4j:log4j:1.2.17");
                            catalog.library("slf4j.api", "org.slf4j:slf4j-api:2.0.6");
                            catalog.library("slf4j-log4j12", "org.slf4j:slf4j-log4j12:1.7.5");
                            catalog.library("dom4j", "dom4j:dom4j:1.6.1");
                            catalog.library("javax.inject", "javax.inject:javax.inject:1");
                            catalog.library("javax.servlet-api", "javax.servlet:javax.servlet-api:3.1.0");
                            catalog.library("jakarta.inject.api", "jakarta.inject:jakarta.inject-api:2.0.1");
                            catalog.library("jakarta.annotation.api", "jakarta.annotation:jakarta.annotation-api:2.1.1");
                            catalog.library("jakarta.servlet.api", "jakarta.servlet:jakarta.servlet-api:6.0.0");
                            catalog.library("commons-fileupload", "commons-fileupload:commons-fileupload:1.5");
                            catalog.library("commons-codec", "commons-codec:commons-codec:1.15");
                            catalog.library("commons-net", "commons-net:commons-net:3.9.0");
                            catalog.library("commons-logging", "commons-logging:commons-logging:1.2");
                            catalog.library("commons-collections", "commons-collections:commons-collections:3.2.2");
                            catalog.library("commons-io", "commons-io:commons-io:2.12.0");
                            catalog.library("commons-lang3", "org.apache.commons:commons-lang3:3.12.0");
                            catalog.library("guava", "com.google.guava", "guava").version("32.1.2-jre");
                            catalog.library("httpclient", "org.apache.httpcomponents:httpclient:4.5.14");
                            catalog.library("mysql", "com.mysql:mysql-connector-j:8.3.0");
                            catalog.library("jedis", "redis.clients:jedis:4.4.1");
                            catalog.library("camunda", "org.camunda.bpm:camunda-engine:7.21.0");
                            catalog.library("java-jwt", "com.auth0:java-jwt:3.2.0");
                            catalog.library("jodd", "org.jodd:jodd:3.3.8");
                            catalog.library("zxing-core", "com.google.zxing:core:3.4.0");
                            catalog.library("zxing-javase", "com.google.zxing:javase:3.4.0");
                            catalog.library("mail", "javax.mail:mail:1.4");
                            catalog.library("jai_imageio", "com.sun.media:jai_imageio:1.1");
                            catalog.library("opencv", "org.opencv:opencv:4.5.5");
                            catalog.library("easypoi-base", "cn.afterturn:easypoi-base:3.0.3");
                            catalog.library("easypoi-annotation", "cn.afterturn:easypoi-annotation:3.0.3");
                            catalog.library("html2image", "com.github.xuwei-k:html2image:0.1.0");
                            // 图片压缩
                            catalog.library("thumbnailator", "net.coobird:thumbnailator:0.4.8");
                            catalog.library("ganymed-ssh2", "ch.ethz.ganymed:ganymed-ssh2:262");
                            catalog.library("aliyun-java-sdk-core", "com.aliyun:aliyun-java-sdk-core:4.4.3");
                            catalog.library("aliyun-java-sdk-alidns", "com.aliyun:aliyun-java-sdk-alidns:2.0.10");
                            catalog.library("stripe-java", "com.stripe:stripe-java:4.4.0");
                            catalog.library("aws-java-sdk-sns", "com.amazonaws:aws-java-sdk-sns:1.11.98");
                            catalog.library("pdf2dom", "net.sf.cssbox:pdf2dom:1.7");
                            catalog.library("pdfbox-core", "org.apache.pdfbox:pdfbox:2.0.15");
                            catalog.library("pdfbox-tools", "org.apache.pdfbox:pdfbox-tools:2.0.12");
                            catalog.library("poi-scratchpad", "org.apache.poi:poi-scratchpad:3.14");
                            catalog.library("poi-ooxml-core", "org.apache.poi:poi-ooxml:3.14");
                            catalog.library("xdocreport", "fr.opensagres.xdocreport:xdocreport:1.0.6");
                            catalog.library("poi-ooxml-schemas", "org.apache.poi:poi-ooxml-schemas:3.14");
                            catalog.library("ooxml-schemas", "org.apache.poi:ooxml-schemas:1.3");
                            catalog.library("junit-jupiter", "org.junit.jupiter:junit-jupiter:5.9.3");
                            catalog.library("h2", "com.h2database:h2:2.1.214");
                            catalog.library("druid", "com.alibaba:druid:1.2.22");
                            catalog.library("dbunit", "org.dbunit:dbunit:2.7.3");
                            catalog.library("mockito-core", "org.mockito:mockito-core:5.11.0");
                            catalog.library("embedded-redis", "it.ozimov:embedded-redis:0.7.3");
                            catalog.library("testcontainers-rabbitmq", "org.testcontainers:rabbitmq:1.19.7");
                            catalog.library("testcontainers-junit", "org.testcontainers:junit-jupiter:1.19.7");
                            catalog.library("testable-all", "com.alibaba.testable:testable-all:0.7.9");
                            catalog.library("testable-processor", "com.alibaba.testable:testable-processor:0.7.9");
                            catalog.library("weixin-java-pay", "com.github.binarywang", "weixin-java-pay").versionRef("weixin-java");
                            catalog.library("weixin-java-mp", "com.github.binarywang", "weixin-java-mp").versionRef("weixin-java");
                            catalog.library("mapstruct.core", "org.mapstruct", "mapstruct").versionRef("mapstruct");
                            catalog.library("mapstruct.processor", "org.mapstruct", "mapstruct-processor").versionRef("mapstruct");
                        })));
    }
}