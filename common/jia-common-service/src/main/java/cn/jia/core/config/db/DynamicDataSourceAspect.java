package cn.jia.core.config.db;

import com.alibaba.druid.pool.DruidDataSource;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 自定义注解 + AOP的方式实现数据源动态切换
 */
@Aspect
@Order(-10)
@Component
@ConditionalOnClass(DruidDataSource.class)
public class DynamicDataSourceAspect {

    @Before("execution(* cn.jia.*.service.*.*(..))")
    public void beforeSwitchDS(JoinPoint point) {
        String typeName = point.getSignature().getDeclaringTypeName();
        String dataSource = typeName.split("\\.")[2];
        DataSourceContextHolder.setDB(dataSource);
    }

    @Before("execution(* org.springframework.security.oauth2.provider.token.DefaultTokenServices.refreshAccessToken(..))")
    public void beforeWorkflowDS(JoinPoint point) {
        DataSourceContextHolder.setDB("user");
    }

    @Before("execution(* org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl.buildProcessEngine(..))")
    public void beforeWorkflowInit(JoinPoint point) {
        DataSourceContextHolder.setDB("workflow");
    }

//    @After("execution(* cn.jia.*.service.*.*(..))")
//    public void afterSwitchDS(JoinPoint point) {
//        DataSourceContextHolder.clearDB();
//    }
}