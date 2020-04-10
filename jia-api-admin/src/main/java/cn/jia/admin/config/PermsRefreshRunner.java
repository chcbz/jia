package cn.jia.admin.config;

import cn.jia.core.entity.Action;
import cn.jia.core.service.ActionService;
import cn.jia.core.util.ClassUtil;
import cn.jia.sms.common.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
@Slf4j
public class PermsRefreshRunner implements CommandLineRunner {
	
	@Autowired
	private ActionService actionService;

	@Override
	public void run(String... arg0) throws Exception {
		String packageName = "cn.jia";
		List<String> classNames = ClassUtil.getClassName(packageName, true);
		log.debug("PermsRefesh Class: " + classNames);
		if (classNames != null) {
			RequestMapping classUrlAnno;
			PreAuthorize methodPermsAnno;
			RequestMapping methodUrlAnno;
			Set<Action> actionList = new HashSet<>();
			for (String className : classNames) {
				if(className.endsWith("Controller")){
					Class<?> clazz = Class.forName(className);
					classUrlAnno = clazz.getAnnotation(RequestMapping.class);
					if(classUrlAnno != null) {
						String moduleName = classUrlAnno.value()[0].replace("/", "");
						String rootUrl = classUrlAnno.value()[0].substring(1);
						Method[] methods = clazz.getMethods();
						for (Method method : methods) {
							methodPermsAnno = method.getAnnotation(PreAuthorize.class);
							methodUrlAnno = method.getAnnotation(RequestMapping.class);
							if (methodPermsAnno != null && methodUrlAnno != null) {
								methodUrlAnno.value();
								Action action = new Action();
								action.setResourceId("jia-api-admin");
								action.setStatus(Constants.PERMS_STATUS_ENABLE);
								action.setModule(moduleName);
								action.setFunc(methodUrlAnno.value()[0].substring(1).replaceAll("/\\{\\w+}", "").replace("/", "_"));
								action.setUrl(rootUrl + methodUrlAnno.value()[0]);
//								action.setDescription(methodPermsAnno.value());
								actionList.add(action);
							}
						}
					}
				}
			}
			actionService.refresh(new ArrayList<>(actionList));
		}
	}

}
