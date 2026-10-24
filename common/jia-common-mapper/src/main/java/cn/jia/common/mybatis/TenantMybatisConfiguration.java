package cn.jia.common.mybatis;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.apache.ibatis.plugin.Interceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TenantIsolationProperties.class)
@ConditionalOnProperty(prefix = "cyf.tenant.mybatis", name = "enabled", havingValue = "true")
public class TenantMybatisConfiguration {
    @Bean
    TenantTablePolicy tenantTablePolicy(TenantIsolationProperties properties) {
        return TenantTablePolicy.of(properties.getTenantTables(), properties.getGlobalTables());
    }

    @Bean
    MybatisPlusInterceptor tenantMybatisPlusInterceptor(TenantTablePolicy tablePolicy) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(
                new MysqlLockingTenantLineInnerInterceptor(
                        new TrustedTenantLineHandler(tablePolicy)));
        return interceptor;
    }

    @Bean
    Interceptor tenantWriteGuard(TenantTablePolicy tablePolicy) {
        return new TenantWriteGuard(tablePolicy);
    }
}
