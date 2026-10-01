package cn.jia.chat.archive.maintenance.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(ArchiveMaintenanceProperties.class)
public class ArchiveMaintenanceConfiguration { }
