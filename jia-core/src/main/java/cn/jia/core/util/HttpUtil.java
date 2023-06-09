package cn.jia.core.util;

import lombok.extern.slf4j.Slf4j;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
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
 * @author chc
 */
@Slf4j
public class HttpUtil {
    /**
     * 向指定URL发送GET方法的请求
     * 
     * @param url 发送请求的URL
     * @return URL 所代表远程资源的响应结果
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
            connection.setRequestProperty("user-agent",
                    "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1;SV1)");
            // 建立实际的连接
            connection.connect();
            // 获取所有响应头字段
            Map<String, List<String>> map = connection.getHeaderFields();
            // 遍历所有的响应头字段
            for (String key : map.keySet()) {
                log.info(key + "--->" + map.get(key));
            }
            // 定义 BufferedReader输入流来读取URL的响应
            in = new BufferedReader(new InputStreamReader(
                    connection.getInputStream()));
            String line;
            while ((line = in.readLine()) != null) {
                result.append(line);
            }
        } catch (Exception e) {
            log.info("发送GET请求出现异常！" + e);
            e.printStackTrace();
        }
        // 使用finally块来关闭输入流
        finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception e2) {
                e2.printStackTrace();
            }
        }
        return result.toString();
    }

    /**
     * 向指定 URL 发送POST方法的请求
     * 
     * @param url
     *            发送请求的 URL
     * @param param
     *            请求参数，请求参数应该是 name1=value1&name2=value2 的形式。
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
            conn.setRequestProperty("user-agent",
                    "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1;SV1)");
            // 发送POST请求必须设置如下两行
            conn.setDoOutput(true);
            conn.setDoInput(true);
            // 获取URLConnection对象对应的输出流
            out = new PrintWriter(conn.getOutputStream());
            // 发送请求参数
            out.print(param);
            // flush输出流的缓冲
            out.flush();
            // 定义BufferedReader输入流来读取URL的响应
            in = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            String line;
            while ((line = in.readLine()) != null) {
                result.append(line);
            }
        } catch (Exception e) {
            log.info("发送 POST 请求出现异常！"+e);
            e.printStackTrace();
        }
        //使用finally块来关闭输出流、输入流
        finally{
            try{
                if(out!=null){
                    out.close();
                }
                if(in!=null){
                    in.close();
                }
            }
            catch(IOException ex){
                ex.printStackTrace();
            }
        }
        return result.toString();
    }    
    
	public static boolean isAjaxRequest(HttpServletRequest request){
		String header = request.getHeader("X-Requested-With");
        return "XMLHttpRequest".equals(header);
	}
	
	/**
	 * 以JSON格式输出
	 * @param response
	 */
	public static void responseJson(HttpServletResponse response, String json) {
		//将实体对象转换为JSON Object转换
		response.setCharacterEncoding("UTF-8");
		response.setContentType("application/json; charset=utf-8");
        try (PrintWriter out = response.getWriter()) {
            out.append(json);
        } catch (IOException e) {
            e.printStackTrace();
        }
	}
	/**
	 * 获取IP地址
	 * @param request
	 * @return
	 */
	public static String getIpAddr(HttpServletRequest request) {
		String ip = request.getHeader("x-forwarded-for");
		if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
			ip = request.getHeader("Proxy-Client-IP");
		}
		if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
			ip = request.getHeader("WL-Proxy-Client-IP");
		}
		if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
			ip = request.getHeader("MB-X-Forwarded-For");
		}
		if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
			ip = request.getRemoteAddr();
		}
		return ip;
	}
	/**
	 * 获取地址参数
	 * @param httpRequest
	 * @return
	 */
	public static String requestParams(HttpServletRequest httpRequest) {
        // 请求参数日志信息
        Map<String, Object> params = new HashMap<>(16);
        Enumeration<?> enume = httpRequest.getParameterNames();
        if (null != enume) {
            while (enume.hasMoreElements()) {
                Object element = enume.nextElement();
                if (null != element) {
                    String paramName = (String) element;
                    String paramValue = httpRequest.getParameter(paramName);
                    params.put(paramName, paramValue);
                }
            }
        }
        return JsonUtil.toJson(params);
    }
	
	/**
     * MethodsTitle:传入的URL中参数的处理
     * @author: xg.chen
     * @date:2016年9月2日 
     * @param url 传入的url ex："http://exp.kunnr.com/so/index.html?kunnrId=16&openid=16#/app/home"
     * @param paramName 参数名
     * @param paramValue 参数值
     * @return
     */
    public static String addUrlValue(String url,String paramName,String paramValue){
        //参数和参数名为空的话就返回原来的URL
        if(StringUtils.isBlank(paramValue) || StringUtils.isBlank(paramName)){
            return url;
        }
        //先很据# ? 将URL拆分成一个String数组
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
        if (StringUtils.isBlank(b)) {
            return url + "?" + paramName + "=" + paramValue;
        }

        // 用&拆p, p1=1&p2=2 ，{p1=1,p2=2}
        String[] bArray = b.split("&");
        StringBuilder newb = new StringBuilder();
        boolean found = false;
        for (String bi : bArray) {
            if (StringUtils.isBlank(bi)) {
                continue;
            }
            String key;
            String value = "";
            // {p1,1}
            String[] biArray = bi.split("=");
            key = biArray[0];
            if (biArray.length > 1) {
                value = biArray[1];
            }

            if (key.equals(paramName)) {
                found = true;
                if (StringUtils.isNotBlank(paramValue)) {
                    newb.append("&").append(key).append("=").append(paramValue);
                }
            } else {
                newb.append("&").append(key).append("=").append(value);
            }
        }
        // 如果没找到，加上
        if (!found && StringUtils.isNotBlank(paramValue)) {
            newb.append("&").append(paramName).append("=").append(paramValue);
        }
        if (StringUtils.isNotBlank(newb.toString())) {
            a = a + "?" + newb.substring(1);
        }
        if (StringUtils.isNotBlank(c)) {
            a = a + "#" + c;
        }
        return a;
    }
    /**
     * MethodsTitle: 从url地址中根据key获取value
     * @author: xg.chen
     * @date:2016年9月2日 
     * @param url  "http://exp.kunnr.com/so/index.html?kunnrId=16&openid=16#/app/home"
     * @param paramName
     * @return
     */
    public static String getUrlValue(String url, String paramName) {
        if(StringUtils.isBlank(paramName)){
            return null;
        }
        // ? #拆开，先把?拆开 a?b#c ->{a,b,c}
        String b = "";
        String[] abcArray = url.split("[?]");
        if (abcArray.length > 1) {
            String bc = abcArray[1];
            String[] bcArray = bc.split("#");
            b = bcArray[0];
        }
        if (StringUtils.isBlank(b)) {
            return null;
        }

        // 用&拆p, p1=1&p2=2 ，{p1=1,p2=2}
        String[] bArray = b.split("&");
        for (String bi : bArray) {
            if (StringUtils.isBlank(bi)) {
                continue;
            }
            String key;
            String value = "";
            // {p1,1}
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
     * @param str html字符串
     * @return 转移后字符串
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
     * @param filenameExtension 文件后缀
     * @return contentType
     */
    public static String fileContentType(String filenameExtension) {
        if ("BMP".equals(filenameExtension) || "bmp".equals(filenameExtension)
                || "BMP".equalsIgnoreCase(filenameExtension)) {
            return "image/bmp";
        }
        if ("GIF".equals(filenameExtension) || "gif".equals(filenameExtension)
                || "GIF".equalsIgnoreCase(filenameExtension)) {
            return "image/gif";
        }
        if ("JPEG".equals(filenameExtension) || "jpeg".equals(filenameExtension) || "JPG".equals(filenameExtension)
                || "jpg".equals(filenameExtension) || "PNG".equals(filenameExtension)
                || "png".equals(filenameExtension) || "JPEG".equalsIgnoreCase(filenameExtension)
                || "JPG".equalsIgnoreCase(filenameExtension) || "PNG".equalsIgnoreCase(filenameExtension)) {
            return "image/jpeg";
        }
        if ("HTML".equals(filenameExtension) || "html".equals(filenameExtension)) {
            return "text/html";
        }
        if ("TXT".equals(filenameExtension) || "txt".equals(filenameExtension)
                || "TXT".equalsIgnoreCase(filenameExtension)) {
            return "text/plain";
        }
        if ("VSD".equals(filenameExtension) || "vsd".equals(filenameExtension)
                || "VSD".equalsIgnoreCase(filenameExtension)) {
            return "application/vnd.visio";
        }
        if ("PPTX".equals(filenameExtension) || "pptx".equals(filenameExtension) || "PPT".equals(filenameExtension)
                || "ppt".equals(filenameExtension) || "PPTX".equalsIgnoreCase(filenameExtension)
                || "PPT".equalsIgnoreCase(filenameExtension)) {
            return "application/vnd.ms-powerpoint";
        }
        if ("DOCX".equals(filenameExtension) || "docx".equals(filenameExtension) || "DOC".equals(filenameExtension)
                || "doc".equals(filenameExtension) || "DOCX".equalsIgnoreCase(filenameExtension)
                || "DOC".equalsIgnoreCase(filenameExtension)) {
            return "application/msword";
        }
        if ("XML".equals(filenameExtension) || "xml".equals(filenameExtension)
                || "XML".equalsIgnoreCase(filenameExtension)) {
            return "text/xml";
        }
        if ("pdf".equals(filenameExtension) || "PDF".equals(filenameExtension)
                || "PDF".equalsIgnoreCase(filenameExtension)) {
            return "application/pdf";
        }
        return null;
    }
}
