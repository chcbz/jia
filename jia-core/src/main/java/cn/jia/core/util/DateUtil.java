package cn.jia.core.util;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Date;

/**
 * 时间操作工具类
 * @author lzh
 *
 */
public class DateUtil {
	
	/**
	 * 默认时间格式：yyyy-MM-dd hh:mm:ss
	 */
	public static final String DATETIME_DEFAULT = "yyyy-MM-dd HH:mm:ss";
	
	/**
	 * yyyy年M月d日 HH点mm分
	 */
	public static final String DATETIME_CN = "yyyy年M月d日 HH点mm分";
	
	/**
	 * yyyyMMddHHmmSS
	 */
	public static final String DATETIME_STRING = "yyyyMMddHHmmss";
	
	/**
	 * 获取指定格式的当前日期的字符串
	 * @param format
	 * @return
	 */
	public static String getDate(String format) {
		try {
			return new SimpleDateFormat(format).format(new Date());
		} catch (Exception e) {
			
		}
		return null;
	}
	
	/**
	 * 获取格式为:yyyy-MM-dd hh:mm:ss的当前日期的字符串
	 * @return
	 */
	public static String getDate() {
		return getDate(DATETIME_DEFAULT);
	}
	
	/**
	 * 获取格式为:yyyy年M月d日 HH点mm分 的当前日期的字符串
	 * @return
	 */
	public static String getCnDate() {
		return getDate(DATETIME_CN);
	}
	
	/**
	 * 获取格式为:yyyyMMddHHmmSS 的当前日期的字符串
	 * @return
	 */
	public static String getDateString() {
		return getDate(DATETIME_STRING);
	}
	/**
	 * 将时间类型转换为yyyy-MM-dd HH:mm:ss格式的时间字符串
	 * @param date
	 * @return
	 */
	public static String formatDate(Date date){
		try {
			return new SimpleDateFormat(DATETIME_DEFAULT).format(date);
		} catch (Exception e) {
			return null;
		}
	}
	
	public static String formatDate(Date date,String pattern){
		try {
			return new SimpleDateFormat(pattern).format(date);
		} catch (Exception e) {
			return null;
		}
	}
	/**
	 * 将格式为yyyy-MM-dd HH:mm:ss的时间字符串转换为时间类型
	 * @param date 时间字符串
	 * @return
	 */
	public static Date parseDate(String date){
		try {
			return new SimpleDateFormat(DATETIME_DEFAULT).parse(date);
		} catch (Exception e) {
			return null;
		}
	}
	
	public static Date parseDate(String date, String format) {
		try {
			return new SimpleDateFormat(format).parse(date);
		} catch (Exception e) {
			return null;
		}
	}
	/**
	 * 获取当天00:00:00的时间
	 * @return
	 */
	public static Date todayStart(){
		Calendar calendar = Calendar.getInstance();
	    calendar.setTime(new Date());
	    calendar.set(Calendar.HOUR_OF_DAY, 0);
	    calendar.set(Calendar.MINUTE, 0);
	    calendar.set(Calendar.SECOND, 0);
	    return calendar.getTime();
	}
	/**
	 * 获取当天23:59:59的时间
	 * @return
	 */
	public static Date todayEnd(){
		Calendar calendar = Calendar.getInstance();
	    calendar.setTime(new Date());
	    calendar.set(Calendar.HOUR_OF_DAY, 23);
	    calendar.set(Calendar.MINUTE, 59);
	    calendar.set(Calendar.SECOND, 59);
	    return calendar.getTime();
	}
	
	/**
	 * 判断指定的时间是否在两个指定的时间内。<br>
	 * 例如：<br>
	 * isBetween("2014-12-1 12:30", "2014-1-1 12:30", "2014-12-5 12:30", "yyyy-MM-dd HH:mi")<br>
	 * isBetween("2014-12-1 12:30", "2014-12-5 12:30", "2014-1-1 12:30", "yyyy-MM-dd HH:mi")
	 * @param arg0      需要判断的时间
	 * @param arg1      靠前的期望时间
	 * @param arg2      靠后的期望时间
	 * @param format 日期格式(参数arg1,arg2不分顺序)
	 * @return
	 */
	public static boolean isBetween(String arg0, String arg1, String arg2, String format) {
		DateFormat df = new SimpleDateFormat(format);
		try {
			long now = df.parse(arg0).getTime();
			long befor = df.parse(arg1).getTime();
			long after = df.parse(arg2).getTime();
			long max = Math.max(befor, after);
			long min = Math.min(befor, after);
			
			if (now >= min && now <= max) {
				return true;
			}
		} catch (ParseException e) {
			throw new RuntimeException("未能将字符串转换成日期格式：" + e);
		}
		return false;
	}
	
	/**
	 * 两个时间比较，获取较晚的时间
	 * @param arg0
	 * @param arg1
	 * @param format
	 * @return
	 */
	public static String after(String arg0, String arg1, String format) {
		String temp = "";
		DateFormat df = new SimpleDateFormat(format);
		try {
			long start = df.parse(arg0).getTime();
			long end = df.parse(arg1).getTime();
			if (start >= end) {
				temp = arg0; 
			} else {
				temp = arg1;
			}
		} catch (ParseException e) {
			throw new RuntimeException("未能将字符串转换成日期格式：" + e);
		}
		return temp;
	}
	
	/**
	 * 两个时间比较，获取较早的时间
	 * @param arg0
	 * @param arg1
	 * @param format
	 * @return
	 */
	public static String before(String arg0, String arg1, String format) {
		String temp = "";
		DateFormat df = new SimpleDateFormat(format);
		try {
			long start = df.parse(arg0).getTime();
			long end = df.parse(arg1).getTime();
			if (start >= end) {
				temp = arg1; 
			} else {
				temp = arg0;
			}
		} catch (ParseException e) {
			throw new RuntimeException("未能将字符串转换成日期格式：" + e);
		}
		return temp;
	}
	
	/**
	 * 比较两个日期（年月日）是否相同（忽略时、分、秒）
	 * @param d1
	 * @param d2
	 * @return
	 */
    public static boolean sameDate(Date d1, Date d2) {
        LocalDate localDate1 = ZonedDateTime.ofInstant(d1.toInstant(), ZoneId.systemDefault()).toLocalDate();
        LocalDate localDate2 = ZonedDateTime.ofInstant(d2.toInstant(), ZoneId.systemDefault()).toLocalDate();
        return localDate1.isEqual(localDate2);
    }
	
	/**
	 * 将秒型的时间(insuranceTime)转化成指定格式format形式
	 * 如：时间是42542779秒转化成格式为"yyyy-MM-dd"
	 * @param insuranceTime 要转化的秒时间
	 * @param format 转化成那种格式如'yyyy-MM-dd'
	 * @return 格式化之后的时间
	 */
	public static String getFormatDate(int insuranceTime, String format){
		Date date = new Date(insuranceTime*1000L);
		SimpleDateFormat sdf = new SimpleDateFormat(format);
		String formatDate = sdf.format(date);
		return formatDate;
	}
	
	/**
	 * 将时间转化成时间戳
	 * @param date
	 * @return
	 */
	public static Long genTime(Date date) {
		String timeStr = String.valueOf(date.getTime());
		return Long.valueOf(timeStr.substring(0, timeStr.length() - 3));
	}
	
	/**
	 * 时间戳转换成日期
	 * @param time
	 * @return
	 */
	public static Date genDate(Long time) {
		return new Date(Long.valueOf(time + "000"));
	}
	
	public static void main(String[] args){
		System.out.println(new Date(1460390400000L));
		System.out.println(System.currentTimeMillis());
	}
}
