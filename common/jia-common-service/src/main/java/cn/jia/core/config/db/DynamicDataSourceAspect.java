package cn.jia.core.config.db;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 自定义注解 + AOP的方式实现数据源动态切换
 */
@Aspect
@Order(-10)
@Component
@ConditionalOnProperty(name = "dynamic.datasource.enable", havingValue = "true")
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