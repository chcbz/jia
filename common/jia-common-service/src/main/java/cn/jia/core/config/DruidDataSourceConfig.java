package cn.jia.core.config;

import cn.jia.core.datasource.DruidSource;
import com.alibaba.druid.pool.DruidDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.SQLException;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(DruidDataSource.class)
public class DruidDataSourceConfig {
	@Bean // 声明其为Bean实例
	@ConfigurationProperties(prefix = "spring.datasource")
	public DruidSource druidSource(){
		return new DruidSource();
	}

	@Bean // 声明其为Bean实例
	public DataSource dataSource(DruidSource config) throws SQLException {
		DruidDataSource datasource = new DruidDataSource();

		datasource.setUrl(config.getDbUrl());
		datasource.setUsername(config.getUsername());
		datasource.setPassword(config.getPassword());
		datasource.setDriverClassName(config.getDriverClassName());

		// configuration
		datasource.setInitialSize(config.getInitialSize());
		datasource.setMinIdle(config.getMinIdle());
		datasource.setMaxActive(config.getMaxActive());
		datasource.setMaxWait(config.getMaxWait());
		datasource.setTimeBetweenEvictionRunsMillis(config.getTimeBetweenEvictionRunsMillis());
		datasource.setMinEvictableIdleTimeMillis(config.getMinEvictableIdleTimeMillis());
		datasource.setValidationQuery(config.getValidationQuery());
		datasource.setTestWhileIdle(config.isTestWhileIdle());
		datasource.setTestOnBorrow(config.isTestOnBorrow());
		datasource.setTestOnReturn(config.isTestOnReturn());
		datasource.setPoolPreparedStatements(config.isPoolPreparedStatements());
		datasource.setMaxPoolPreparedStatementPerConnectionSize(config.getMaxPoolPreparedStatementPerConnectionSize());

		datasource.setFilters(config.getFilters());

		return datasource;
	}
}