package cn.jia.sms.config;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.alibaba.druid.pool.DruidDataSource;

import cn.jia.core.datasource.DruidSource;

@Configuration
public class DataSourceConfig {
	@Autowired
	private DruidSource config;
	
	@Bean // 声明其为Bean实例
	@ConfigurationProperties(prefix = "spring.datasource")
	public DruidSource druidSource(){
		return new DruidSource();
	}

	@Bean // 声明其为Bean实例
	@Primary // 在同样的DataSource中，首先使用被标注的DataSource
	@ConfigurationProperties(prefix = "spring.datasource")
	public DataSource dataSource() throws SQLException {
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