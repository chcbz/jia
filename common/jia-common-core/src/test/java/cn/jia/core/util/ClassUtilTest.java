package cn.jia.core.util;

import cn.jia.core.entity.JsonResult;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.List;

@Slf4j
class ClassUtilTest {
    @Test
    void getClassName() {
        String packageName = "cn.jia.core.util";
        // List<String> classNames = getClassName(packageName);
        List<String> classNames = ClassUtil.getClassName(packageName, false);
        for (String className : classNames) {
            log.info(className);
        }
    }

    @Test
    void getClassNameByJar() {
		log.info(String.valueOf(ClassUtil.getClassName("jar:file:/D:/workspace/shunwei/jia/project/jia-api-admin/target/jia-api-admin.jar!/BOOT-INF/lib/jia-core-api-1.0.0-SNAPSHOT.jar!/cn/jia", true)));
        /*URL url = new URL("jar:file:/D:/workspace/shunwei/jia/project/jia-api-admin/target/jia-api-admin.jar!/BOOT-INF/lib/jia-core-api-1.0.0-SNAPSHOT.jar");
        InputStream is = url.openStream();
        FileUtil.create(is, "D:/tmp/tmp.jar");
        JarFile jarFile = new JarFile(new File("D:/tmp/tmp.jar"));
        log.info(jarFile);*/
        JsonResult obj = new JsonResult();
        ClassUtil.setAttribute(obj, "status", 2100, Integer.TYPE);
        Integer status = ClassUtil.getAttribute(obj, "status");
        log.info(String.valueOf(status));
    }
}