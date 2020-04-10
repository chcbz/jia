package cn.jia.admin.config.db;

import cn.jia.core.datasource.DruidSource;
import com.alibaba.druid.pool.DruidDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class DataSourceConfig {
	@Autowired
	private DruidSource druidSource;
	@Autowired
	private DruidSource druidSourceAdmin;
	@Autowired
	private DruidSource druidSourceApi;
	@Value("#{'${spring.datasource.admin.multiFlag}'.split(',')}")
	private List<String> multiFlagAdmin;
	@Value("#{'${spring.datasource.api.multiFlag}'.split(',')}")
	private List<String> multiFlagApi;
	
	@Bean // 声明其为Bean实例
	@ConfigurationProperties(prefix = "spring.datasource")
	public DruidSource druidSource(){
		return new DruidSource();
	}

	@Bean // 声明其为Bean实例
	@ConfigurationProperties(prefix = "spring.datasource.admin")
	public DruidSource druidSourceAdmin(){
		return new DruidSource();
	}

	@Bean // 声明其为Bean实例
	@ConfigurationProperties(prefix = "spring.datasource.api")
	public DruidSource druidSourceApi(){
		return new DruidSource();
	}

	@Bean // 声明其为Bean实例
	public DataSource dataSourceApi() throws SQLException {
		DruidDataSource dataSource = new DruidDataSource();

		dataSource.setUrl(druidSourceApi.getDbUrl());
		dataSource.setUsername(druidSourceApi.getUsername());
		dataSource.setPassword(druidSourceApi.getPassword());
		dataSource.setDriverClassName(druidSourceApi.getDriverClassName());

		// configuration
		dataSource.setInitialSize(druidSource.getInitialSize());
		dataSource.setMinIdle(druidSource.getMinIdle());
		dataSource.setMaxActive(druidSource.getMaxActive());
		dataSource.setMaxWait(druidSource.getMaxWait());
		dataSource.setTimeBetweenEvictionRunsMillis(druidSource.getTimeBetweenEvictionRunsMillis());
		dataSource.setMinEvictableIdleTimeMillis(druidSource.getMinEvictableIdleTimeMillis());
		dataSource.setValidationQuery(druidSource.getValidationQuery());
		dataSource.setTestWhileIdle(druidSource.isTestWhileIdle());
		dataSource.setTestOnBorrow(druidSource.isTestOnBorrow());
		dataSource.setTestOnReturn(druidSource.isTestOnReturn());
		dataSource.setPoolPreparedStatements(druidSource.isPoolPreparedStatements());
		dataSource.setMaxPoolPreparedStatementPerConnectionSize(druidSource.getMaxPoolPreparedStatementPerConnectionSize());

		dataSource.setFilters(druidSource.getFilters());

		return dataSource;
	}

	@Bean // 声明其为Bean实例
	public DataSource dataSourceAdmin() throws SQLException {
		DruidDataSource dataSource = new DruidDataSource();

		dataSource.setUrl(druidSourceAdmin.getDbUrl());
		dataSource.setUsername(druidSourceAdmin.getUsername());
		dataSource.setPassword(druidSourceAdmin.getPassword());
		dataSource.setDriverClassName(druidSourceAdmin.getDriverClassName());

		// configuration
		dataSource.setInitialSize(druidSource.getInitialSize());
		dataSource.setMinIdle(druidSource.getMinIdle());
		dataSource.setMaxActive(druidSource.getMaxActive());
		dataSource.setMaxWait(druidSource.getMaxWait());
		dataSource.setTimeBetweenEvictionRunsMillis(druidSource.getTimeBetweenEvictionRunsMillis());
		dataSource.setMinEvictableIdleTimeMillis(druidSource.getMinEvictableIdleTimeMillis());
		dataSource.setValidationQuery(druidSource.getValidationQuery());
		dataSource.setTestWhileIdle(druidSource.isTestWhileIdle());
		dataSource.setTestOnBorrow(druidSource.isTestOnBorrow());
		dataSource.setTestOnReturn(druidSource.isTestOnReturn());
		dataSource.setPoolPreparedStatements(druidSource.isPoolPreparedStatements());
		dataSource.setMaxPoolPreparedStatementPerConnectionSize(druidSource.getMaxPoolPreparedStatementPerConnectionSize());

		dataSource.setFilters(druidSource.getFilters());

		return dataSource;
	}

	@Primary
	@Bean(name = "dataSource")
	public DataSource dynamicDataSource() throws SQLException {
		DynamicDataSource dynamicDataSource = new DynamicDataSource();
		DataSource dataSourceAdmin = dataSourceAdmin();
		DataSource dataSourceApi = dataSourceApi();
		// 配置多数据源
		Map<Object, Object> dsMap = new HashMap<>();
		multiFlagAdmin.forEach(flag -> dsMap.put(flag, dataSourceAdmin));
		multiFlagApi.forEach(flag -> dsMap.put(flag, dataSourceApi));
		// 默认数据源
		dynamicDataSource.setDefaultTargetDataSource(dataSourceApi);
		dynamicDataSource.setTargetDataSources(dsMap);
		return dynamicDataSource;
	}

	//配置@Transactional注解事物
	@Bean
	public PlatformTransactionManager transactionManager() throws SQLException {
		return new DataSourceTransactionManager(dynamicDataSource());
	}
}