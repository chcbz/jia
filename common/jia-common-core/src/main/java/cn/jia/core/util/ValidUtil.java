package cn.jia.core.util;

import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.exception.EsRuntimeException;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Hashtable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 正则表达式验证类
 * @author lzh
 *
 */
public class ValidUtil {
	private static final Pattern MOBILE_PATTERN = Pattern.compile("[4-6]{1}[0-9]{2}[0-9]{3}[0-9]{4}$");
	private static final Pattern CAR_NUMBER_PATTERN = Pattern.compile("^[一-龥][A-Z][A-Z_0-9]{5}$");
	private static final Pattern NUMBER_PATTERN = Pattern.compile("[0-9]*");
	private static final Pattern DATE_PATTERN = Pattern.compile("^((\\d{2}(([02468][048])|([13579][26]))[\\-/\\s]?((((0?[13578])|(1[02]))[\\-/\\s]?((0?[1-9])|([1-2][0-9])|(3[01])))|(((0?[469])|(11))[\\-/\\s]?((0?[1-9])|([1-2][0-9])|(30)))|(0?2[\\-/\\s]?((0?[1-9])|([1-2][0-9])))))|(\\d{2}(([02468][1235679])|([13579][01345789]))[\\-/\\s]?((((0?[13578])|(1[02]))[\\-/\\s]?((0?[1-9])|([1-2][0-9])|(3[01])))|(((0?[469])|(11))[\\-/\\s]?((0?[1-9])|([1-2][0-9])|(30)))|(0?2[\\-/\\s]?((0?[1-9])|(1[0-9])|(2[0-8]))))))(\\s(((0?[0-9])|([1-2][0-3])):([0-5]?[0-9])((\\s)|(:([0-5]?[0-9])))))?");
	private static final Pattern EMAIL_PATTERN = Pattern.compile("^([a-zA-Z0-9_\\-.]+)@((\\[[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.)|(([a-zA-Z0-9\\-]+\\.)+))([a-zA-Z]{2,4}|[0-9]{1,3})(]?)$");

	/**
	 * 验证是否是合法的手机号
	 * @param str 手机号
	 * @return 校验结果
	 */
	public static boolean isMobile(String str) {
		if(StringUtil.isNotBlank(str)){
			try {
				Matcher m = MOBILE_PATTERN.matcher(str);
				return m.matches();
			} catch (Exception e) {
				return false;
			}
		}else{
			return false;
		}
    }

	/**
	 * 验证是否车牌号码
	 * @param str 车牌号码
	 * @return 校验结果
	 */
	public static boolean isCarNumber(String str)
	{
		if(StringUtil.isNotBlank(str)){
			//验证车牌信息
			Matcher m = CAR_NUMBER_PATTERN.matcher(str);
			return m.matches();
		}else{
			return false;
		}
	}
	/**
	 * 验证是否是合法身份证号
	 * @param idStr	身份证号
	 * @return 校验结果
	 */
	public static boolean isIdNumber(String idStr){
		if(StringUtil.isNotBlank(idStr)){
			try {
				String[] valCodeArr = { "1", "0", "x", "9", "8", "7", "6", "5", "4", "3", "2" };
		        String[] wi = { "7", "9", "10", "5", "8", "4", "2", "1", "6", "3", "7", "9", "10", "5", "8", "4", "2" };
		        String ai = "";
		        // ================ 号码的长度 15位或18位 ================
		        if (idStr.length() != 15 && idStr.length() != 18) {
		            return false;
		        }
		        // ================ 数字 除最后以为都为数字 ================
		        if (idStr.length() == 18) {
		            ai = idStr.substring(0, 17);
		        } else if (idStr.length() == 15) {
		            ai = idStr.substring(0, 6) + "19" + idStr.substring(6, 15);
		        }
		        if (!isNumeric(ai)) {
		            return false;
		        }
		        // ================ 出生年月是否有效 ================
				// 年份
		        String strYear = ai.substring(6, 10);
				// 月份
		        String strMonth = ai.substring(10, 12);
				// 月份
		        String strDay = ai.substring(12, 14);
		        if (!isDate(strYear + "-" + strMonth + "-" + strDay)) {
		            return false;
		        }
		        GregorianCalendar gc = new GregorianCalendar();
		        SimpleDateFormat s = new SimpleDateFormat("yyyy-MM-dd");
		        try {
		            if ((gc.get(Calendar.YEAR) - Integer.parseInt(strYear)) > 150 ||
		            		(gc.getTime().getTime() - s.parse(strYear + "-" + strMonth + "-" + strDay).getTime()) < 0) {
		                return false;
		            }
		        } catch (NumberFormatException e) {
		            e.printStackTrace();
		        } catch (java.text.ParseException e) {
		            e.printStackTrace();
		        }
		        if (Integer.parseInt(strMonth) > 12 || Integer.parseInt(strMonth) == 0) {
		            return false;
		        }
		        if (Integer.parseInt(strDay) > 31 || Integer.parseInt(strDay) == 0) {
		            return false;
		        }
		        // ================ 地区码是否有效 ================
		        Hashtable<?, ?> h = getAreaCode();
		        if (h.get(ai.substring(0, 2)) == null) {
		            return false;
		        }
		        // ================ 判断最后一位的值 ================
		        int totalmulAiWi = 0;
		        for (int i = 0; i < 17; i++) {
		            totalmulAiWi = totalmulAiWi
		                    + Integer.parseInt(String.valueOf(ai.charAt(i)))
		                    * Integer.parseInt(wi[i]);
		        }
		        int modValue = totalmulAiWi % 11;
		        String strVerifyCode = valCodeArr[modValue];
		        ai = ai + strVerifyCode;

		        if (idStr.length() == 18) {
					return ai.equals(idStr);
		        } else {
		            return true;
		        }
			} catch (Exception e) {
				return false;
			}
		}else{
			return false;
		}
	}
	/**
     * 功能：判断字符串是否为数字
     * @param str 字符串
     * @return 校验结果
     */
    public static boolean isNumeric(String str) {
    	if(StringUtil.isBlank(str)){
    		return false;
    	}
    	try {
    		Matcher isNum = NUMBER_PATTERN.matcher(str);
			return isNum.matches();
		} catch (Exception e) {
			return false;
		}
    }
    /**
     * 功能：判断字符串是否为日期格式
     * @param strDate 日期字符串
     * @return 校验结果
     */
    public static boolean isDate(String strDate) {
    	if(StringUtil.isBlank(strDate)){
    		return false;
    	}
    	try {
    		Matcher m = DATE_PATTERN.matcher(strDate);
			return m.matches();
		} catch (Exception e) {
			return false;
		}
    }
    /**
     * 校验邮箱地址
     * @param str 邮箱地址
     * @return 校验结果
     */
    public static boolean isEmail(String str) {
    	if(StringUtil.isBlank(str)){
    		return false;
    	}
    	try {
    		Matcher m = EMAIL_PATTERN.matcher(str);
			return m.matches();
		} catch (Exception e) {
			return false;
		}
    }
    /**
     * 功能：设置地区编码
     * @return Hashtable 对象
     */
    private static Hashtable<String, String> getAreaCode() {
        Hashtable<String, String> hashtable = new Hashtable<>();
        hashtable.put("11", "北京");
        hashtable.put("12", "天津");
        hashtable.put("13", "河北");
        hashtable.put("14", "山西");
        hashtable.put("15", "内蒙古");
        hashtable.put("21", "辽宁");
        hashtable.put("22", "吉林");
        hashtable.put("23", "黑龙江");
        hashtable.put("31", "上海");
        hashtable.put("32", "江苏");
        hashtable.put("33", "浙江");
        hashtable.put("34", "安徽");
        hashtable.put("35", "福建");
        hashtable.put("36", "江西");
        hashtable.put("37", "山东");
        hashtable.put("41", "河南");
        hashtable.put("42", "湖北");
        hashtable.put("43", "湖南");
        hashtable.put("44", "广东");
        hashtable.put("45", "广西");
        hashtable.put("46", "海南");
        hashtable.put("50", "重庆");
        hashtable.put("51", "四川");
        hashtable.put("52", "贵州");
        hashtable.put("53", "云南");
        hashtable.put("54", "西藏");
        hashtable.put("61", "陕西");
        hashtable.put("62", "甘肃");
        hashtable.put("63", "青海");
        hashtable.put("64", "宁夏");
        hashtable.put("65", "新疆");
        hashtable.put("71", "台湾");
        hashtable.put("81", "香港");
        hashtable.put("82", "澳门");
        hashtable.put("91", "国外");
        return hashtable;
    }

	/**
	 * 非空断言，为空则跑异常
	 *
	 * @param obj 要判断的对象
	 * @param args 对象名称
	 */
	public static void assertNotNull(Object obj, Object... args) {
		if (obj == null) {
			throw new EsRuntimeException(EsErrorConstants.DATA_NOT_FOUND, args);
		}
	}
}
