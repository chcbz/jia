package cn.jia.agent.config;

import cn.jia.agent.service.HostingRentAdmissionService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the disabled-by-default R00 admission gate without enabling paid hosting. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentHostingRentProperties.class)
public class AgentHostingRentConfiguration {
    @Bean
    public HostingRentAdmissionService hostingRentAdmissionService(
            AgentHostingRentProperties properties) {
        return new HostingRentAdmissionService(properties);
    }
}
