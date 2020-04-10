package cn.jia.core.util;

import org.apache.commons.lang3.StringUtils;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.DecimalFormat;
import java.util.*;

public class DataUtil {

    /**
     * 将字符串分割成指定的字符串列表
     *
     * @param values
     * @param regex
     * @return
     */
    public static List<String> toStringList(String values, String regex) {
        List<String> list = null;
        String[] items = values.split(regex);
        if (items != null && items.length > 0) {
            list = new ArrayList<String>();
            for (String item : items) {
                if (!StringUtils.isEmpty(item)) {
                    list.add(item);
                }
            }
        }
        return list;
    }

    /**
     * 将字符串分割成指定的整型数据列表，无法转换的数字不计入列表
     *
     * @param values
     * @param regex
     * @return
     */
    public static List<Integer> toIntegerList(String values, String regex) {
        List<Integer> list = null;
        String[] items = values.split(regex);
        if (items != null && items.length > 0) {
            list = new ArrayList<Integer>();
            for (String item : items) {
                if (StringUtils.isNumeric(item)) {
                    list.add(Integer.valueOf(item));
                }
            }
        }
        return list;
    }

    /**
     * 保留n位小数点
     *
     * @param d 原始数值
     * @param n 保留的小数点位数
     * @return
     */
    public static Double formatDecimal(Double d, Integer n) {
        BigDecimal b = new BigDecimal(d);
        return b.setScale(n, BigDecimal.ROUND_HALF_UP).doubleValue();
    }

    /**
     * 格式化价格为0.0格式
     *
     * @param price
     * @return
     */
    public static String formatPrice(String price) {
        try {
            float fmtPrice = Float.parseFloat(price);
            DecimalFormat dfm = new DecimalFormat("0.0");// 格式化价格为0.0格式
            return dfm.format(fmtPrice);
        } catch (Exception e) {
            return price;
        }
    }

    /**
     * 格式化价格为0.00格式
     *
     * @param price
     * @return
     */
    public static String formatPrice2(String price) {
        try {
            float fmtPrice = Float.parseFloat(price);
            DecimalFormat dfm = new DecimalFormat("0.00");// 格式化价格为0.00格式
            return dfm.format(fmtPrice);
        } catch (Exception e) {
            return price;
        }
    }

    /**
     * 浮点小数乘法运算
     *
     * @param param1 乘数
     * @param param2 被乘数
     * @return
     */
    public static String floatMultiply(String param1, String param2) {
        String value = "";
        try {
            BigDecimal bdValue = new BigDecimal(param1);
            BigDecimal bdPer = new BigDecimal(param2);
            double dbValue = bdValue.multiply(bdPer).doubleValue();
            value = String.valueOf(dbValue);
        } catch (Exception e) {
            e.printStackTrace();
        }
        ;
        return value;
    }

    /**
     * 浮点小数加法运算
     *
     * @param param1 加数
     * @param param2 被加数
     * @return
     */
    public static String floatAdd(String param1, String param2) {
        String value = "";
        try {
            BigDecimal bdValue = new BigDecimal(param1);
            BigDecimal bdPer = new BigDecimal(param2);
            double dbValue = bdValue.add(bdPer).doubleValue();
            value = String.valueOf(dbValue);
        } catch (Exception e) {
            e.printStackTrace();
        }
        ;
        return value;
    }

    /**
     * 获取客户端IP
     *
     * @return 客户端IP
     */
    public static String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("x-forwarded-for");
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
            if (ip.equals("127.0.0.1")) {
                // 根据网卡取本机配置的IP
                InetAddress inet = null;
                try {
                    inet = InetAddress.getLocalHost();
                } catch (UnknownHostException e) {
                    e.printStackTrace();
                }
                ip = inet.getHostAddress();
            }
        }
        // 对于通过多个代理的情况，第一个IP为客户端真实IP,多个IP按照','分割
        if (ip != null && ip.length() > 15) { // "***.***.***.***".length() = 15
            if (ip.indexOf(",") > 0) {
                ip = ip.substring(0, ip.indexOf(","));
            }
        }
        return ip;
    }

    /**
     * 创建指定数量的随机字符串
     *
     * @param numberFlag 是否是数字
     * @param length     字符串长度
     * @return
     */
    public static String getRandom(boolean numberFlag, int length) {
        StringBuilder retStr;
        String strTable = numberFlag ? "1234567890"
                : "123456789abcdefghijkmnpqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        int len = strTable.length();
        boolean bDone = true;
        do {
            retStr = new StringBuilder();
            int count = 0;
            for (int i = 0; i < length; i++) {
                double dblR = Math.random() * len;
                int intR = (int) Math.floor(dblR);
                char c = strTable.charAt(intR);
                if (('0' <= c) && (c <= '9')) {
                    count++;
                }
                retStr.append(strTable.charAt(intR));
            }
            if (count >= 1) {
                bDone = false;
            }
        } while (bDone);

        return retStr.toString();
    }

    /**
     * 产生去掉-的UUID
     *
     * @return
     */
    public static String getUUID() {
        UUID uuid = UUID.randomUUID();
        String str = uuid.toString();
        // 去掉"-"符号  
        String temp = str.substring(0, 8) + str.substring(9, 13) + str.substring(14, 18) + str.substring(19, 23) + str.substring(24);
        return temp;
    }

    /**
     * 判断是否是整数
     *
     * @param str
     * @return
     */
    public static boolean isInteger(String str) {
        boolean flag = false;
        try {
            Integer.parseInt(str);
            flag = true;
        } catch (Exception e) {
            // TODO: handle exception
        }
        return flag;
    }

    /**
     * 两个整数相除得到百分比
     *
     * @param param1 被除数
     * @param param2 除数
     * @return
     */
    public static String percent(int param1, int param2) {
        String result = "0";
        try {
            if (param2 > 0) {
                BigDecimal bcValue = new BigDecimal(param1);
                BigDecimal cValue = new BigDecimal(param2);
                double dbValue = bcValue.divide(cValue, 2, BigDecimal.ROUND_HALF_UP).doubleValue();
                bcValue = new BigDecimal(dbValue);
                cValue = new BigDecimal(100);
                dbValue = bcValue.multiply(cValue).doubleValue();
                DecimalFormat dfm = new DecimalFormat("0");
                result = dfm.format(dbValue) + "%";
            }
        } catch (Exception e) {
            // TODO: handle exception
        }
        return result;
    }

    public static final char UNDERLINE = '_';

    /**
     * 将驼峰格式字符串转成下划线格式
     *
     * @param param
     * @return
     */
    public static String camelToUnderline(String param) {
        if (param == null || "".equals(param.trim())) {
            return "";
        }
        int len = param.length();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = param.charAt(i);
            if (Character.isUpperCase(c)) {
                sb.append(UNDERLINE);
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 将下划线格式字符串转成驼峰格式
     *
     * @param param
     * @return
     */
    public static String underlineToCamel(String param) {
        if (param == null || "".equals(param.trim())) {
            return "";
        }
        int len = param.length();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = param.charAt(i);
            if (c == UNDERLINE) {
                if (++i < len) {
                    sb.append(Character.toUpperCase(param.charAt(i)));
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 获取两坐标的直线距离（米）
     *
     * @param longt1 起始坐标经度
     * @param lat1   起始坐标纬度
     * @param longt2 目标坐标经度
     * @param lat2   目标坐标纬度
     * @return
     */
    public static double getDistance(double longt1, double lat1, double longt2, double lat2) {
        double R = 6371229; // 地球的半径
        double x, y, distance;
        x = (longt2 - longt1) * Math.PI * R
                * Math.cos(((lat1 + lat2) / 2) * Math.PI / 180) / 180;
        y = (lat2 - lat1) * Math.PI * R / 180;
        distance = Math.hypot(x, y);
        return formatDecimal(distance, 0);
    }

    /**
     * 将参数转化成Map
     *
     * @param name1
     * @param value1
     * @param name2
     * @param value2
     * @return
     */
    public static <V, V1 extends V, V2 extends V> Map<String, V> toMap(String name1, V1 value1, String name2, V2 value2) {
        return populateMap(new HashMap<String, V>(), name1, value1, name2, value2);
    }

    /**
     * 将参数转化成Map
     *
     * @param map
     * @param data
     * @return
     */
    @SuppressWarnings("unchecked")
    private static <K, V> Map<String, V> populateMap(Map<String, V> map, Object... data) {
        for (int i = 0; i < data.length; ) {
            map.put((String) data[i++], (V) data[i++]);
        }
        return map;
    }

    /**
     * 将元数据前补零，补后的总长度为指定的长度，以字符串的形式返回
     *
     * @param sourceDate
     * @param formatLength 字符总长度
     * @return 重组后的数据
     */
    public static String frontCompWithZore(int sourceDate, int formatLength) {
        String newString = String.format("%0" + formatLength + "d", sourceDate);
        return newString;
    }

    //byte 与 int 的相互转换
    public static byte intToByte(int x) {
        return (byte) x;
    }

    public static int byteToInt(byte b) {
        //Java 总是把 byte 当做有符处理；我们可以通过将其和 0xFF 进行二进制与得到它的无符值
        return b & 0xFF;
    }

    //byte 数组与 int 的相互转换
    public static int byteArrayToInt(byte[] b) {
        return   b[3] & 0xFF |
                (b[2] & 0xFF) << 8 |
                (b[1] & 0xFF) << 16 |
                (b[0] & 0xFF) << 24;
    }

    public static byte[] intToByteArray(int a) {
        return new byte[] {
                (byte) ((a >> 24) & 0xFF),
                (byte) ((a >> 16) & 0xFF),
                (byte) ((a >> 8) & 0xFF),
                (byte) (a & 0xFF)
        };
    }

    //byte 数组与 long 的相互转换
    public static byte[] longToBytes(long data) {
        byte[] bytes = new byte[8];
        bytes[0] = (byte) (data & 0xff);
        bytes[1] = (byte) ((data >> 8) & 0xff);
        bytes[2] = (byte) ((data >> 16) & 0xff);
        bytes[3] = (byte) ((data >> 24) & 0xff);
        bytes[4] = (byte) ((data >> 32) & 0xff);
        bytes[5] = (byte) ((data >> 40) & 0xff);
        bytes[6] = (byte) ((data >> 48) & 0xff);
        bytes[7] = (byte) ((data >> 56) & 0xff);
        return bytes;
    }

    public static long bytesToLong(byte[] bytes) {
        return (0xffL & (long) bytes[0]) | (0xff00L & ((long) bytes[1] << 8)) | (0xff0000L & ((long) bytes[2] << 16)) | (0xff000000L & ((long) bytes[3] << 24))
                | (0xff00000000L & ((long) bytes[4] << 32)) | (0xff0000000000L & ((long) bytes[5] << 40)) | (0xff000000000000L & ((long) bytes[6] << 48)) | (0xff00000000000000L & ((long) bytes[7] << 56));
    }

        public static void main(String[] args) {
        System.out.println(getUUID());
        System.out.println(DataUtil.getRandom(false, 8));
        System.out.println(getDistance(113.7254, 23.00607, 23.00610350447056, 113.7253981177823) / 1000);
    }
}
