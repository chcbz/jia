package cn.jia.test;

import com.github.springtestdbunit.bean.DatabaseConfigBean;
import com.github.springtestdbunit.bean.DatabaseDataSourceConnectionFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * DbUnit数据源配置
 */
@Configuration
public class DBUnitConfiguration {
    @Bean
    public DatabaseConfigBean dbUnitDatabaseConfig() {
        DatabaseConfigBean bean = new DatabaseConfigBean();
        bean.setAllowEmptyFields(true);
        return bean;
    }
    @Bean
    public DatabaseDataSourceConnectionFactoryBean dbUnitDatabaseConnection(
            DatabaseConfigBean dbUnitDatabaseConfig,
            DataSource dataSource) {
        DatabaseDataSourceConnectionFactoryBean bean = new DatabaseDataSourceConnectionFactoryBean(dataSource);
        bean.setDatabaseConfig(dbUnitDatabaseConfig);
        return bean;
    }
}
