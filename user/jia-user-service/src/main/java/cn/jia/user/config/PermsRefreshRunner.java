package cn.jia.user.config;

import cn.jia.core.common.EsConstants;
import cn.jia.core.config.SpringContextHolder;
import cn.jia.user.entity.PermsEntity;
import cn.jia.user.service.PermsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Slf4j
@ConditionalOnExpression("${user.permit.refresh.enable:false}")
public class PermsRefreshRunner implements CommandLineRunner {

    @Autowired
    private PermsService permsService;

    @Value("${spring.application.name:}")
    private String resourceId;

    @Override
    public void run(String... arg0) {
        AtomicReference<RequestMapping> classUrlAnno = new AtomicReference<>();
        AtomicReference<PreAuthorize> methodPermsAnno = new AtomicReference<>();
        AtomicReference<RequestMapping> methodUrlAnno = new AtomicReference<>();
        List<PermsEntity> actionList = new ArrayList<>();

        Map<String, Object> beansMap =
				SpringContextHolder.getApplicationContext().getBeansWithAnnotation(Controller.class);
        beansMap.values().forEach(value -> {
            Class<?> clazz = AopUtils.getTargetClass(value);
            classUrlAnno.set(clazz.getAnnotation(RequestMapping.class));
            if (classUrlAnno.get() != null) {
                String moduleName = classUrlAnno.get().value()[0].replace("/", "");
                String rootUrl = classUrlAnno.get().value()[0].substring(1);
                Method[] methods = ReflectionUtils.getAllDeclaredMethods(clazz);
                for (Method method : methods) {
                    if (method.isAnnotationPresent(PreAuthorize.class) &&
                            method.isAnnotationPresent(RequestMapping.class)) {
                        methodPermsAnno.set(method.getAnnotation(PreAuthorize.class));
                        methodUrlAnno.set(method.getAnnotation(RequestMapping.class));
                        PermsEntity action = new PermsEntity();
                        action.setResourceId(resourceId);
                        action.setStatus(EsConstants.PERMS_STATUS_ENABLE);
                        action.setModule(moduleName);
                        action.setFunc(methodUrlAnno.get().value()[0].substring(1)
                                .replaceAll("/\\{\\w+}", "").replace("/", "_"));
                        action.setUrl(rootUrl + methodUrlAnno.get().value()[0]);
						action.setDescription(methodPermsAnno.get().value());
                        actionList.add(action);
                    }
                }
            }
        });
        permsService.refresh(actionList);
    }
}
