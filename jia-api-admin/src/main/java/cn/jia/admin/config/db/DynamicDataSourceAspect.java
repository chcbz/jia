package cn.jia.admin.config.db;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 自定义注解 + AOP的方式实现数据源动态切换
 */
@Aspect
@Order(-10)
@Component
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
//    @Before("execution(* org.activiti.engine.impl.asyncexecutor.*.*(..))")
//    public void beforeWorkflowRunJob(JoinPoint point) {
//        DataSourceContextHolder.setDB("workflow");
//    }
   /* @Before("execution(* org.activiti.spring.SpringTransactionInterceptor.*(..))")
    public void beforeWorkflowCommandExecutorImpl(JoinPoint point) {
        DataSourceContextHolder.setDB("workflow");
    }
    @Before("execution(* org.activiti.engine.impl.interceptor.*.*(..))")
    public void beforeWorkflowCommandExecutor(JoinPoint point) {
        DataSourceContextHolder.setDB("workflow");
    }*/


//    @After("execution(* cn.jia.*.service.*.*(..))")
//    public void afterSwitchDS(JoinPoint point) {
//        DataSourceContextHolder.clearDB();
//    }
}