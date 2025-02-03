package cn.jia.core.util;

import lombok.extern.slf4j.Slf4j;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLConnection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP操作的工具类。
 * 提供发送GET和POST请求、处理JSON响应等方法。
 * <p>
 * 作者: chc
 */
@Slf4j
public class HttpUtil {

    /**
     * 向指定URL发送GET方法的请求
     *
     * @param url 发送请求的URL
     * @return URL所代表远程资源的响应结果
     */
    public static String sendGet(String url) {
        StringBuilder result = new StringBuilder();
        BufferedReader in = null;
        try {
            URL realUrl = new URL(url);
            // 打开和URL之间的连接
            URLConnection connection = realUrl.openConnection();
            // 设置通用的请求属性
            connection.setRequestProperty("accept", "*/*");
            connection.setRequestProperty("connection", "Keep-Alive");
            connection.setRequestProperty("user-agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1;SV1)");
            // 建立实际的连接
            connection.connect();
            // 获取所有响应头字段
            Map<String, List<String>> map = connection.getHeaderFields();
            // 遍历所有的响应头字段
            for (String key : map.keySet()) {
                log.info("{}--->{}", key, map.get(key));
            }
            // 定义 BufferedReader输入流来读取URL的响应
            in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String line;
            while ((line = in.readLine()) != null) {
                result.append(line);
            }
        } catch (Exception e) {
            log.error("发送GET请求出现异常！", e);
        } finally {
            // 使用finally块来关闭输入流
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception e2) {
                log.error("关闭输入流异常！", e2);
            }
        }
        return result.toString();
    }

    /**
     * 向指定URL发送POST方法的请求
     *
     * @param url   发送请求的URL
     * @param param 请求参数，请求参数应该是 name1=value1&name2=value2 的形式
     * @return 所代表远程资源的响应结果
     */
    public static String sendPost(String url, String param) {
        PrintWriter out = null;
        BufferedReader in = null;
        StringBuilder result = new StringBuilder();
        try {
            URL realUrl = new URL(url);
            // 打开和URL之间的连接
            URLConnection conn = realUrl.openConnection();
            // 设置通用的请求属性
            conn.setRequestProperty("accept", "*/*");
            conn.setRequestProperty("connection", "Keep-Alive");
            conn.setRequestProperty("user-agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1;SV1)");
            // 发送POST请求必须设置如下两行
            conn.setDoOutput(true);
            conn.setDoInput(true);
            // 获取URLConnection对象对应的输出流
            out = new PrintWriter(conn.getOutputStream());
            // 发送请求参数
            out.print(param);
            // flush输出流的缓冲
            out.flush();
            // 定义BufferedReader输入流来读取URL响应
            in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String line;
            while ((line = in.readLine()) != null) {
                result.append(line);
            }
        } catch (Exception e) {
            log.error("发送POST请求出现异常！", e);
        } finally {
            // 使用finally块来关闭输出流、输入流
            try {
                if (out != null) {
                    out.close();
                }
                if (in != null) {
                    in.close();
                }
            } catch (IOException ex) {
                log.error("关闭流异常！", ex);
            }
        }
        return result.toString();
    }

    /**
     * 判断是否为Ajax请求
     *
     * @param request HttpServletRequest对象
     * @return 如果是Ajax请求返回true，否则返回false
     */
    public static boolean isAjaxRequest(HttpServletRequest request) {
        String header = request.getHeader("X-Requested-With");
        return "XMLHttpRequest".equals(header);
    }

    /**
     * 以JSON格式输出
     *
     * @param response HttpServletResponse对象
     * @param json     JSON字符串
     */
    public static void responseJson(HttpServletResponse response, String json) {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json; charset=utf-8");
        try (PrintWriter out = response.getWriter()) {
            out.append(json);
        } catch (IOException e) {
            log.error("输出JSON异常！", e);
        }
    }

    /**
     * 获取IP地址
     *
     * @param request HttpServletRequest对象
     * @return IP地址字符串
     */
    public static String getIpAddr(HttpServletRequest request) {
        String ip = request.getHeader("x-forwarded-for");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("MB-X-Forwarded-For");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    /**
     * 获取请求参数并转换为JSON字符串
     *
     * @param httpRequest HttpServletRequest���象
     * @return 请求参数的JSON字符串
     */
    public static String requestParams(HttpServletRequest httpRequest) {
        Map<String, Object> params = new HashMap<>(16);
        Enumeration<?> enume = httpRequest.getParameterNames();
        if (enume != null) {
            while (enume.hasMoreElements()) {
                Object element = enume.nextElement();
                if (element != null) {
                    String paramName = (String) element;
                    String paramValue = httpRequest.getParameter(paramName);
                    params.put(paramName, paramValue);
                }
            }
        }
        return JsonUtil.toJson(params);
    }

    /**
     * 向URL中添加参数
     *
     * @param url        原始URL
     * @param paramName  参数名
     * @param paramValue 参数值
     * @return 添加参数后的URL
     */
    public static String addUrlValue(String url, String paramName, String paramValue) {
        if (StringUtil.isBlank(paramValue) || StringUtil.isBlank(paramName)) {
            return url;
        }
        String a = "";
        String b = "";
        String c = "";

        String[] abcArray = url.split("[?]");
        a = abcArray[0];
        if (abcArray.length > 1) {
            String bc = abcArray[1];
            String[] bcArray = bc.split("#");
            b = bcArray[0];
            if (bcArray.length > 1) {
                c = bcArray[1];
            }
        }
        if (StringUtil.isBlank(b)) {
            return url + "?" + paramName + "=" + paramValue;
        }

        String[] bArray = b.split("&");
        StringBuilder newb = new StringBuilder();
        boolean found = false;
        for (String bi : bArray) {
            if (StringUtil.isBlank(bi)) {
                continue;
            }
            String key;
            String value = "";
            String[] biArray = bi.split("=");
            key = biArray[0];
            if (biArray.length > 1) {
                value = biArray[1];
            }

            if (key.equals(paramName)) {
                found = true;
                if (StringUtil.isNotBlank(paramValue)) {
                    newb.append("&").append(key).append("=").append(paramValue);
                }
            } else {
                newb.append("&").append(key).append("=").append(value);
            }
        }
        if (!found && StringUtil.isNotBlank(paramValue)) {
            newb.append("&").append(paramName).append("=").append(paramValue);
        }
        if (StringUtil.isNotBlank(newb.toString())) {
            a = a + "?" + newb.substring(1);
        }
        if (StringUtil.isNotBlank(c)) {
            a = a + "#" + c;
        }
        return a;
    }

    /**
     * 从URL中获取参数值
     *
     * @param url       URL字符串
     * @param paramName 参数名
     * @return 参数值，如果未找到则返回null
     */
    public static String getUrlValue(String url, String paramName) {
        if (StringUtil.isBlank(url) || StringUtil.isBlank(paramName)) {
            return null;
        }
        String b = url;
        String[] abcArray = url.split("[?]");
        if (abcArray.length > 1) {
            String bc = abcArray[1];
            String[] bcArray = bc.split("#");
            b = bcArray[0];
        }
        if (StringUtil.isBlank(b)) {
            return null;
        }

        String[] bArray = b.split("&");
        for (String bi : bArray) {
            if (StringUtil.isBlank(bi)) {
                continue;
            }
            String key;
            String value = "";
            String[] biArray = bi.split("=");
            key = biArray[0];
            if (biArray.length > 1) {
                value = biArray[1];
            }
            if (key.equals(paramName)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 特殊字符转义
     *
     * @param str 要转义的字符串
     * @return 转义后的字符串
     */
    public static String escape(String str) {
        str = str.replace("'", "&apos;");
        str = str.replace("\"", "&quot;");
        str = str.replace("<", "&lt;");
        str = str.replace(">", "&gt;");
        str = str.replace("(", "&#40;");
        str = str.replace("&", "&amp;");
        str = str.replace(")", "&#41;");
        str = str.replace("\r", "");
        str = str.replace("\n", "");
        str = str.replace("\t", "");
        return str;
    }

    /**
     * 根据文件后缀获取contentType
     *
     * @param filenameExtension 文件后缀
     * @return contentType字符串
     */
    public static String fileContentType(String filenameExtension) {
        if ("BMP".equalsIgnoreCase(filenameExtension)) {
            return "image/bmp";
        }
        if ("GIF".equalsIgnoreCase(filenameExtension)) {
            return "image/gif";
        }
        if ("JPEG".equalsIgnoreCase(filenameExtension) || "JPG".equalsIgnoreCase(filenameExtension) || "PNG".equalsIgnoreCase(filenameExtension)) {
            return "image/jpeg";
        }
        if ("HTML".equalsIgnoreCase(filenameExtension)) {
            return "text/html";
        }
        if ("TXT".equalsIgnoreCase(filenameExtension)) {
            return "text/plain";
        }
        if ("VSD".equalsIgnoreCase(filenameExtension)) {
            return "application/vnd.visio";
        }
        if ("PPTX".equalsIgnoreCase(filenameExtension) || "PPT".equalsIgnoreCase(filenameExtension)) {
            return "application/vnd.ms-powerpoint";
        }
        if ("DOCX".equalsIgnoreCase(filenameExtension) || "DOC".equalsIgnoreCase(filenameExtension)) {
            return "application/msword";
        }
        if ("XML".equalsIgnoreCase(filenameExtension)) {
            return "text/xml";
        }
        if ("PDF".equalsIgnoreCase(filenameExtension)) {
            return "application/pdf";
        }
        return null;
    }

    /**
     * 匹配urlPatterns
     *
     * @param requestURI 请求URI
     * @param urlPatterns URL模式
     * @return 是否匹配
     */
    public static boolean matchUrlPatterns(String requestURI, String[] urlPatterns) {
        if (urlPatterns == null) {
            return false;
        }
        for (String urlPattern : urlPatterns) {
            if (requestURI.matches(urlPattern.replace("?", ".").replace("*", ".*"))) {
                return true;
            }
        }
        return false;
    }
}