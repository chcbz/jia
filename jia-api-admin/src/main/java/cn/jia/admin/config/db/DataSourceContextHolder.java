package cn.jia.admin.config.db;

import lombok.extern.slf4j.Slf4j;

@Slf4j
class DataSourceContextHolder {

    private static final ThreadLocal<String> contextHolder = new ThreadLocal<>();

    // 设置数据源名
    static void setDB(String dbType) {
        log.info("切换到{"+dbType+"}数据源");
        contextHolder.set(dbType);

        /*DataSource dataSource = SpringContextHolder.getBean(DataSource.class);
        //---------------------修改mybatis的数据源-----------------------
        //修改MyBatis的数据源
        SqlSessionFactoryBean sqlSessionFactoryBean = SpringContextHolder.getBean(SqlSessionFactoryBean.class);
        Environment environment = sqlSessionFactoryBean.getObject().getConfiguration().getEnvironment();
        Field dataSourceField = environment.getClass().getDeclaredField("dataSource");
        dataSourceField.setAccessible(true);//跳过检查
        dataSourceField.set(environment,dataSource);//修改mybatis的数据源*/
    }

    // 获取数据源名
    static String getDB() {
        return (contextHolder.get());
    }

    // 清除数据源名
    static void clearDB() {
        log.info("清除数据源");
        contextHolder.remove();
    }
}