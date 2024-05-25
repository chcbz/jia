package cn.jia.core.config.db;

import cn.jia.core.config.SpringContextHolder;
import cn.jia.core.datasource.DruidSource;
import cn.jia.core.util.BeanUtil;
import cn.jia.core.util.StringUtil;
import com.alibaba.druid.pool.DruidDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(DruidDataSource.class)
public class DataSourceConfig {
    @Bean // 声明其为Bean实例
    @ConfigurationProperties(prefix = "spring.datasource")
    public DruidSource druidSource() {
        return new DruidSource();
    }

    public Map<Object, Object> druidSourceMulti(DruidSource druidSource) {
        Map<Object, Object> druidSourceMap = new HashMap<>();
        int i = 1;
        while (true) {
            String multiFlag = SpringContextHolder.getProperty("spring.datasource." + i + ".multiFlag", String.class);
            if (StringUtil.isEmpty(multiFlag)) {
                break;
            }
            DruidSource dataSource = new DruidSource();
            BeanUtil.copyPropertiesIgnoreNull(druidSource, dataSource);
            dataSource.setDbUrl(SpringContextHolder.getProperty("spring.datasource." + i + ".dbUrl", String.class));
            dataSource.setUsername(
                    SpringContextHolder.getProperty("spring.datasource." + i + ".username", String.class));
            dataSource.setPassword(
                    SpringContextHolder.getProperty("spring.datasource." + i + ".password", String.class));
            dataSource.setDriverClassName(
                    SpringContextHolder.getProperty("spring.datasource." + i + ".driverClassName", String.class));
            for (String dataSourceName : multiFlag.split(",")) {
                druidSourceMap.put(dataSourceName, dataSource);
            }
            i++;
        }
        return druidSourceMap;
    }

    @Primary
    @Bean(name = "dataSource")
    public DataSource dynamicDataSource(DruidSource druidSource) {
        DynamicDataSource dynamicDataSource = new DynamicDataSource();
        // 配置多数据源
        Map<Object, Object> dsMap = druidSourceMulti(druidSource);
        // 默认数据源
        dynamicDataSource.setDefaultTargetDataSource(dsMap.values().iterator().next());
        dynamicDataSource.setTargetDataSources(dsMap);
        return dynamicDataSource;
    }

    //配置@Transactional注解事物
    @Bean
    public PlatformTransactionManager transactionManager(DruidSource druidSource) {
        return new DataSourceTransactionManager(dynamicDataSource(druidSource));
    }
}