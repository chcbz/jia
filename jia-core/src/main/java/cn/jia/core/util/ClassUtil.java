package cn.jia.core.util;

import cn.jia.core.entity.JSONResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class ClassUtil {
    private final static Logger log = LoggerFactory.getLogger(ClassUtil.class);

    public static void main(String[] args) throws Exception {
		/*String packageName = "cn.jia.core.util";
		// List<String> classNames = getClassName(packageName);
		List<String> classNames = getClassName(packageName, false);
		if (classNames != null) {
			for (String className : classNames) {
				System.out.println(className);
			}
		}*/
//		System.out.println(getClassNameByJar("jar:file:/D:/workspace/shunwei/jia/project/jia-api-admin/target/jia-api-admin.jar!/BOOT-INF/lib/jia-api-core-1.0.0-SNAPSHOT.jar!/cn/jia", true));
        /*URL url = new URL("jar:file:/D:/workspace/shunwei/jia/project/jia-api-admin/target/jia-api-admin.jar!/BOOT-INF/lib/jia-api-core-1.0.0-SNAPSHOT.jar");
        InputStream is = url.openStream();
        FileUtil.create(is, "D:/tmp/tmp.jar");
        JarFile jarFile = new JarFile(new File("D:/tmp/tmp.jar"));
        System.out.println(jarFile);*/
		JSONResult obj = new JSONResult();
		setAttribute(obj, "status", 2100, Integer.TYPE);
		Integer status = getAttribute(obj, "status");
        System.out.println(status);
    }

    /**
     * 获取某包下（包括该包的所有子包）所有类
     *
     * @param packageName 包名
     * @return 类的完整名称
     */
    public static List<String> getClassName(String packageName) {
        return getClassName(packageName, true);
    }

    /**
     * 获取某包下所有类
     *
     * @param packageName  包名
     * @param childPackage 是否遍历子包
     * @return 类的完整名称
     */
    public static List<String> getClassName(String packageName, boolean childPackage) {
        List<String> fileNames = new ArrayList<>();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        String packagePath = packageName.replace(".", "/");
        try {
            Enumeration<URL> urls = loader.getResources(packagePath);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                log.info("getClassName URL: " + url.toString());
                String type = url.getProtocol();
                if (type.equals("file")) {
                    fileNames.addAll(getClassNameByFile(url.getPath(), null, childPackage));
                } else if (type.equals("jar")) {
                    fileNames.addAll(getClassNameByJar(url.getPath(), childPackage));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return fileNames;
    }

    /**
     * 从项目文件获取某包下所有类
     *
     * @param filePath     文件路径
     * @param className    类名集合
     * @param childPackage 是否遍历子包
     * @return 类的完整名称
     */
    private static List<String> getClassNameByFile(String filePath, List<String> className, boolean childPackage) {
        List<String> myClassName = new ArrayList<>();
        File file = new File(filePath);
        File[] childFiles = file.listFiles();
        if (childFiles != null) {
            for (File childFile : childFiles) {
                if (childFile.isDirectory()) {
                    if (childPackage) {
                        myClassName.addAll(getClassNameByFile(childFile.getPath(), myClassName, true));
                    }
                } else {
                    String childFilePath = childFile.getPath();
                    if (childFilePath.endsWith(".class")) {
                        childFilePath = childFilePath.substring(childFilePath.indexOf(File.separator + "classes") + 9,
                                childFilePath.lastIndexOf("."));
                        childFilePath = childFilePath.replace(File.separator, ".");
                        myClassName.add(childFilePath);
                    }
                }
            }
        }

        return myClassName;
    }

    /**
     * 从jar获取某包下所有类
     *
     * @param jarPath      jar文件路径
     * @param childPackage 是否遍历子包
     * @return 类的完整名称
     */
    private static List<String> getClassNameByJar(String jarPath, boolean childPackage) {
        log.info("getClassNameByJar jarPath: " + jarPath);
        List<String> myClassName = new ArrayList<>();
        JarFile jarFile;
        try {
            int pathIndex = jarPath.lastIndexOf("!");
            String jarFilePath = jarPath.substring(0, pathIndex);
            if (jarFilePath.endsWith(".jar")) {
                String fileUrl = jarPath.substring(0, pathIndex);
                if (fileUrl.contains("!")) {
                    fileUrl = "jar:" + fileUrl;
                }
                URL url = new URL(fileUrl);
                InputStream is = url.openStream();
                String filename = url.getPath().substring(url.getPath().lastIndexOf("/") + 1);
                filename = FileUtil.getName(filename) + "_" + DateUtil.getDateString() + "." + FileUtil.getExtension(filename);
                FileUtil.create(is, "/tmp/" + filename);
                jarFile = new JarFile(new File("/tmp/" + filename));
            } else {
                jarFile = new JarFile(jarFilePath.substring(5, jarFilePath.lastIndexOf("!")));
            }
            log.info("getClassNameByJar jarFile: " + jarFile.getName());

            String packagePath = jarPath.substring(pathIndex + 2);

            Enumeration<JarEntry> entrys = jarFile.entries();
            while (entrys.hasMoreElements()) {
                JarEntry jarEntry = entrys.nextElement();
                String entryName = jarEntry.getName();
                if (entryName.endsWith(".class")) {
                    if (childPackage) {
                        int idx = entryName.indexOf(packagePath);
                        if (idx != -1) {
                            entryName = entryName.replace("/", ".").substring(idx, entryName.lastIndexOf("."));
                            myClassName.add(entryName);
                        }
                    } else {
                        int index = entryName.lastIndexOf("/");
                        String myPackagePath;
                        if (index != -1) {
                            myPackagePath = entryName.substring(0, index);
                        } else {
                            myPackagePath = entryName;
                        }
                        if (myPackagePath.endsWith(packagePath)) {
                            entryName = packagePath + "." + entryName.substring(index + 1);
                            myClassName.add(entryName);
                        }
                    }
                }
            }
            jarFile.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return myClassName;
    }

    /**
     * 从所有jar中搜索该包，并获取该包下所有类
     *
     * @param urls         URL集合
     * @param packagePath  包路径
     * @param childPackage 是否遍历子包
     * @return 类的完整名称
     */
    private static List<String> getClassNameByJars(URL[] urls, String packagePath, boolean childPackage) {
        List<String> myClassName = new ArrayList<>();
        if (urls != null) {
			for (URL url : urls) {
				String urlPath = url.getPath();
				// 不必搜索classes文件夹
				if (urlPath.endsWith("classes/")) {
					continue;
				}
				String jarPath = urlPath + "!/" + packagePath;
				myClassName.addAll(getClassNameByJar(jarPath, childPackage));
			}
        }
        return myClassName;
    }

    public static String getJarRealPath(Class<?> c) {
        String jarWholePath = c.getProtectionDomain().getCodeSource().getLocation().getFile();
        try {
            jarWholePath = java.net.URLDecoder.decode(jarWholePath, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            System.out.println(e.toString());
        }
        return new File(jarWholePath).getParentFile().getAbsolutePath();
    }

    /**
     * @param o          操作对象
     * @param methodName 方法名
     * @param attName    属性名
     * @param value      值
     * @return get方法返回实际值 set方法返回操作后对象
     */
    private static Object Operation(Object o, String methodName, String attName, Class<?> paramType, Object value) {
        // 方法赋值出错标志
        boolean opErr = false;
        Object res = null;
        Class<?> type = o.getClass();
        try {
            Method method = null;
            if (methodName.contains("get")) {
                // get方法
                // 获取方法
                method = type.getMethod(methodName);
                // 执行
                res = method.invoke(o);
            } else {
                // set方法
                // 当没有传入参数类型时通过value获取参数类型
                paramType = paramType == null ? value.getClass() : paramType;
                // 获取方法
                method = type.getMethod(methodName, paramType);
                // 执行
                method.invoke(o, value);
                res = o;
            }
        } catch (Exception e) {
            // 通过get/set方法操作属性失败
            opErr = true;
            System.err.println("[WARN] 直接对属性'" + attName + "进行操作(不借助get/set方法).");
        }

        if (opErr) {
            // 通过打破封装方式直接对值进行操作
            try {
                Field field = null;
                // 获取属性
                field = type.getDeclaredField(attName);
                // 打破封装
                field.setAccessible(true);

                if (methodName.contains("get")) {
                    // get方法
                    // 获取属性值
                    res = field.get(o);
                } else {
                    // set方法
                    // 设置属性值
                    field.set(o, value);
                    res = o;
                }
            } catch (Exception e) {
                //两种方法都操作失败
                System.err.println("[ERROR] 属性'" + attName + "'操作失败.");
            }
        }

        return res;
    }

    /**
     * 设置属性值
     *
     * @param o         操作对象
     * @param attName   属性名
     * @param value     参数值
     * @param paramType 参数类型
     * @return 操作后对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T setAttribute(T o, String attName, Object value, Class<?> paramType) {
        if (o == null || attName == null || attName.isEmpty()) {
            return null;
        }
        String methodName = attNameHandle("set", attName);

        return (T) Operation(o, methodName, attName, paramType, value);
    }

    /**
     * 获取属性值
     *
     * @param o          操作对象
     * @param attName    属性名
     * @return
     */
    @SuppressWarnings("unchecked")
    public static <T> T getAttribute(Object o, String attName) {
        if (o == null || attName == null || attName.isEmpty()) {
            return null;
        }
        String methodName = attNameHandle("get", attName);

        return (T) Operation(o, methodName, attName, null, null);
    }

    /**
     * 属性名处理
     *
     * @param method  方法(get/set)
     * @param attName 属性名
     * @return
     */
    private static String attNameHandle(String method, String attName) {
        StringBuilder res = new StringBuilder(method);
        // 属性只有一个字母
        if (attName.length() == 1) {
            res.append(attName.toUpperCase());
        } else {
            // 属性包含两个字母及以上
            char[] charArray = attName.toCharArray();
			// 当前两个字符为小写时,将首字母转换为大写
            if (Character.isLowerCase(charArray[0]) && Character.isLowerCase(charArray[1])) {
                res.append(Character.toUpperCase(charArray[0]));
                res.append(attName.substring(1));
            } else {
                res.append(attName);
            }
        }

        return res.toString();
    }
}