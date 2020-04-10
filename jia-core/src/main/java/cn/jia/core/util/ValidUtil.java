package cn.jia.core.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Hashtable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * 正则表达式验证类
 * @author lzh
 *
 */
public class ValidUtil {

	/**
	 * 验证是否是合法的手机号
	 * @param str	手机号
	 * @return 
	 */
	public static boolean isMobile(String str) {   
		if(StringUtils.isNotBlank(str)){
			try {
				Pattern p = null;  
		        Matcher m = null;  
		        boolean b = false;   
//		        p = Pattern.compile("^[1][3,4,5,8][0-9]{9}$"); // 验证手机号  
		        p = Pattern.compile("[4-6]{1}[0-9]{2}[0-9]{3}[0-9]{4}$");
		        m = p.matcher(str);  
		        b = m.matches();   
		        return b;  
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
	 * @return 
	 */
	public static boolean isCarNumber(String str)
	{
		if(StringUtils.isNotBlank(str)){
			Pattern p=null;
			Matcher m=null;
			boolean b=false;
			p=Pattern.compile("^[\u4e00-\u9fa5]{1}[A-Z]{1}[A-Z_0-9]{5}$");  //验证车牌信息
			m=p.matcher(str);
			b=m.matches();
			return b;
		}else{
			return false;
		}
	}
	/**
	 * 验证是否是合法身份证号
	 * @param idno	身份证号
	 * @return
	 */
	public static boolean isIdNumber(String IDStr){
		if(StringUtils.isNotBlank(IDStr)){
			try {
				String[] ValCodeArr = { "1", "0", "x", "9", "8", "7", "6", "5", "4", "3", "2" };
		        String[] Wi = { "7", "9", "10", "5", "8", "4", "2", "1", "6", "3", "7", "9", "10", "5", "8", "4", "2" };
		        String Ai = "";
		        // ================ 号码的长度 15位或18位 ================
		        if (IDStr.length() != 15 && IDStr.length() != 18) {
		            return false;
		        }
		        // ================ 数字 除最后以为都为数字 ================
		        if (IDStr.length() == 18) {
		            Ai = IDStr.substring(0, 17);
		        } else if (IDStr.length() == 15) {
		            Ai = IDStr.substring(0, 6) + "19" + IDStr.substring(6, 15);
		        }
		        if (isNumeric(Ai) == false) {
		            return false;
		        }
		        // ================ 出生年月是否有效 ================
		        String strYear = Ai.substring(6, 10);// 年份
		        String strMonth = Ai.substring(10, 12);// 月份
		        String strDay = Ai.substring(12, 14);// 月份
		        if (isDate(strYear + "-" + strMonth + "-" + strDay) == false) {
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
		        Hashtable<?, ?> h = GetAreaCode();
		        if (h.get(Ai.substring(0, 2)) == null) {
		            return false;
		        }
		        // ================ 判断最后一位的值 ================
		        int TotalmulAiWi = 0;
		        for (int i = 0; i < 17; i++) {
		            TotalmulAiWi = TotalmulAiWi
		                    + Integer.parseInt(String.valueOf(Ai.charAt(i)))
		                    * Integer.parseInt(Wi[i]);
		        }
		        int modValue = TotalmulAiWi % 11;
		        String strVerifyCode = ValCodeArr[modValue];
		        Ai = Ai + strVerifyCode;
		 
		        if (IDStr.length() == 18) {
		            if (Ai.equals(IDStr) == false) {
		                return false;
		            }
		        } else {
		            return true;
		        }
		        return true;
			} catch (Exception e) {
				return false;
			}
		}else{
			return false;
		}
	}
	/**
     * 功能：判断字符串是否为数字
     * @param str
     * @return
     */
    public static boolean isNumeric(String str) {
    	if(StringUtils.isBlank(str)){
    		return false;
    	}
    	try {
    		Pattern pattern = Pattern.compile("[0-9]*");
    		Matcher isNum = pattern.matcher(str);
    		if (isNum.matches()) {
    			return true;
    		} else {
    			return false;
    		}
		} catch (Exception e) {
			return false;
		}
    }
    /**
     * 功能：判断字符串是否为日期格式
     * @param str
     * @return
     */
    public static boolean isDate(String strDate) {
    	if(StringUtils.isBlank(strDate)){
    		return false;
    	}
    	try {
    		Pattern pattern = Pattern.compile("^((\\d{2}(([02468][048])|([13579][26]))[\\-\\/\\s]?((((0?[13578])|(1[02]))[\\-\\/\\s]?((0?[1-9])|([1-2][0-9])|(3[01])))|(((0?[469])|(11))[\\-\\/\\s]?((0?[1-9])|([1-2][0-9])|(30)))|(0?2[\\-\\/\\s]?((0?[1-9])|([1-2][0-9])))))|(\\d{2}(([02468][1235679])|([13579][01345789]))[\\-\\/\\s]?((((0?[13578])|(1[02]))[\\-\\/\\s]?((0?[1-9])|([1-2][0-9])|(3[01])))|(((0?[469])|(11))[\\-\\/\\s]?((0?[1-9])|([1-2][0-9])|(30)))|(0?2[\\-\\/\\s]?((0?[1-9])|(1[0-9])|(2[0-8]))))))(\\s(((0?[0-9])|([1-2][0-3]))\\:([0-5]?[0-9])((\\s)|(\\:([0-5]?[0-9])))))?{1}");
    		Matcher m = pattern.matcher(strDate);
    		if (m.matches()) {
    			return true;
    		} else {
    			return false;
    		}
		} catch (Exception e) {
			return false;
		}
    }
    /**
     * 校验邮箱地址
     * @param str
     * @return
     */
    public static boolean isEmail(String str) {
    	if(StringUtils.isBlank(str)){
    		return false;
    	}
    	try {
    		Pattern pattern = Pattern.compile("^([a-zA-Z0-9_\\-\\.]+)@((\\[[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.)|(([a-zA-Z0-9\\-]+\\.)+))([a-zA-Z]{2,4}|[0-9]{1,3})(\\]?)$");
    		Matcher m = pattern.matcher(str);
    		if (m.matches()) {
    			return true;
    		} else {
    			return false;
    		}
		} catch (Exception e) {
			return false;
		}
    }
    /**
     * 功能：设置地区编码
     * @return Hashtable 对象
     */
    private static Hashtable<String, String> GetAreaCode() {
        Hashtable<String, String> hashtable = new Hashtable<String, String>();
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
}
