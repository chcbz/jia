package cn.jia.core.util;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * 编码转换工具
 * @author lzh
 */
public class Helper {
	/**
	 * 根据当前传入的code编码生成指定位数size的编码自动+1的新编码
	 * 
	 * @param code
	 * @param size
	 *            编码位数
	 * @return 新生成的编码
	 */
	public static String generateCode(String code, int size) {
		int newCodeNumber = 1;
		if (StringUtils.isNotBlank(code)) {
			String codeNumber = code;
			if (!"0".equals(code)) {
				codeNumber = code.replaceAll("^(0+)", "");
			}
			newCodeNumber = Integer.parseInt(codeNumber) + 1;
		}
		String newCode = String.valueOf(newCodeNumber);
		return StringUtils.leftPad(newCode, size, "0");
	}

	/**
	 * 检测指定结果集中指定的字段是否存在
	 * 
	 * @param rs
	 * @param column
	 */
	public static boolean isExistColumn(ResultSet rs, String columnName) {
		try {
			if (rs.getObject(columnName) != null) {
				return true;
			}
		} catch (SQLException e) {
			// 此处不抛出异常
			// e.printStackTrace();
		}
		return false;
	}
	
	/**
	 * 判断指定字符串是否是大于1的数字
	 * 
	 * @param arg
	 * @return
	 */
	public static boolean isNumbers(String arg) {
		return Pattern.matches("[1-9][0-9]*", arg);
	}

	public static boolean isMoney(String arg) {
		return Pattern.matches("[0-9]+([.]{1}[0-9]+)*", arg);
	}

	public static boolean isDnseg(String value) {
		return Pattern.matches("[0-9]+([,]{1}[0-9]+)*", value);
	}

	public static boolean isDigit(String arg) {
		return Pattern.matches("[0-9]*", arg);
	}

	/**
	 * 根据业务编号生成订单号(20位)
	 * 
	 * @param code
	 *            业务编号
	 * @return
	 */
	public static String generateOrderCode(String code) {
		return DateUtil.getDate("yyyyMMdd")
				+ StringUtils.leftPad(code, 12, "0");
	}

	/**
	 * 根据当前截止日期和需要延长的天数计算延长后的最终日期，返回格式为yyyy-MM-dd 00:59:59，不是精确的具体时间
	 * 
	 * @param curDateTime
	 *            当前截至日期时间字符串
	 * @param day
	 *            需要延长的天数
	 * @return
	 */
	public static String getValidDateTime(String curDateTime, int day) {
		if (day <= 0) {
			return curDateTime;
		}
		// 如果用户的上网截止日期为空，则取当前时间来计算截止日期
		if (StringUtils.isBlank(curDateTime)) {
			curDateTime = DateUtil.getDate();
		}
		Date validDate = DateUtil.parseDate(curDateTime);
		Calendar calendar = Calendar.getInstance();
		calendar.setTime(validDate);
		calendar.add(Calendar.DATE, day);
		// 计算之后最终的截止日期
		return new SimpleDateFormat("yyyy-MM-dd 00:59:59").format(calendar
				.getTime());
	}

	/**
	 * 根据当前截止日期和需要延长的天数计算延长后的最终日期，返回格式为yyyy-MM-dd HH:mm:ss的精确时间
	 * 
	 * @param curDateTime
	 *            当前截至日期时间字符串
	 * @param day
	 *            需要延长的天数
	 * @return
	 */
	public static String getValidRealDateTime(String curDateTime, int day) {
		if (day <= 0) {
			return curDateTime;
		}
		// 如果用户的上网截止日期为空，则取当前时间来计算截止日期
		if (StringUtils.isBlank(curDateTime)) {
			curDateTime = DateUtil.getDate();
		}
		Date validDate = DateUtil.parseDate(curDateTime);
		Calendar calendar = Calendar.getInstance();
		calendar.setTime(validDate);
		calendar.add(Calendar.DATE, day);
		// 计算之后最终的截止日期
		return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(calendar
				.getTime());
	}
	
	/**
	 * 返回当前日期字符串+year年后的日期，返回格式为yyyy-MM-dd
	 * 
	 * @param curDate	当前日期字符串
	 * @param day		增加的年数
	 * @return
	 */
	public static String dateAddByYear(String curDate, int year) {
		if (year <= 0) {
			return curDate;
		}
		if (StringUtils.isBlank(curDate)) {
			curDate = DateUtil.getDate("yyyy-MM-dd");
		}
		Date validDate = DateUtil.parseDate(curDate, "yyyy-MM-dd");
		Calendar calendar = Calendar.getInstance();
		calendar.setTime(validDate);
		calendar.add(Calendar.YEAR, year);
		// 计算之后最终的日期
		return new SimpleDateFormat("yyyy-MM-dd").format(calendar.getTime());
	}

	/**
	 * 将指定的字符串用指定的字符（字符串）分割成数字列表， 例如：将“1,2,3,,,”分割成List<Integer>
	 * 
	 * @param str
	 * @param reg
	 * @return
	 */
	public static List<Integer> getNumbers(String str, String reg) {
		List<Integer> numbers = new ArrayList<Integer>();
		if (!StringUtils.isEmpty(str)) {
			String[] items = str.split(reg);
			if (items != null && items.length > 0) {
				for (String item : items) {
					if (Helper.isDigit(item)) {
						numbers.add(Integer.valueOf(item));
					}
				}
			}
		}
		return numbers;
	}

	public static List<String> getStrings(String str, String reg) {
		List<String> strings = new ArrayList<String>();
		if (!StringUtils.isEmpty(str)) {
			String[] items = str.split(reg);
			if (items != null && items.length > 0) {
				for (String item : items) {
					if (!StringUtils.isEmpty(item)) {
						strings.add(item);
					}
				}
			}
		}
		return strings;
	}

	public static List<Integer> getNumbers(int[] array) {
		List<Integer> numbers = new ArrayList<Integer>();
		if (array != null) {
			for (Integer arg : array) {
				numbers.add(arg);
			}
		}
		return numbers;
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
	 * 创建指定数量的随机字符串
	 * 
	 * @param numberFlag
	 *            是否是数字
	 * @param length
	 *            字符串长度
	 * @return
	 */
	public static String getRandom(boolean numberFlag, int length) {
		String retStr = "";
		String strTable = numberFlag ? "1234567890"
				: "1234567890abcdefghijkmnpqrstuvwxyz";
		int len = strTable.length();
		boolean bDone = true;
		do {
			retStr = "";
			int count = 0;
			for (int i = 0; i < length; i++) {
				double dblR = Math.random() * len;
				int intR = (int) Math.floor(dblR);
				char c = strTable.charAt(intR);
				if (('0' <= c) && (c <= '9')) {
					count++;
				}
				retStr += strTable.charAt(intR);
			}
			if (count >= 2) {
				bDone = false;
			}
		} while (bDone);

		return retStr;
	}

	/**
	 * 根据业务代码code生成指定位数的字母和数字组成的唯一字符串
	 * 
	 * @param code
	 *            业务代码
	 * @param length
	 *            长度
	 * @return
	 */
	public static String getUniqueKey(String code, int length) {
		if (StringUtils.isNotBlank(code)) {
			int len = length - code.length();
			if (len > 0) {
				return getRandom(false, len) + code;
			}
			return code;
		}
		return null;
	}

	/**
	 * 整型列表转换为逗号分隔的字符串
	 * 
	 * @param list
	 *            整型数字的集合
	 * @return
	 */
	public static String listToSplitString(List<Integer> list) {
		if (list != null && list.size() > 0) {
			String splitStr = null;
			for (Integer intStr : list) {
				if (StringUtils.isBlank(splitStr)) {
					splitStr = intStr.toString();
				} else {
					splitStr = splitStr + "," + intStr.toString();
				}
			}
			return splitStr;
		}
		return null;
	}
	
	public static Integer [] findDiff(Integer [] src,Integer [] dest){
		List<Integer> temp = new ArrayList<Integer>();
		List<Integer> diffList = new ArrayList<Integer>();
		for(Integer i : src){
			for(Integer j : dest){
				if(i==j){
					temp.add(i);
				}
			}
		}
		for(Integer i: src){
			if(!temp.contains(i)){
				diffList.add(i);
			}
		}
		Integer [] diffArr = new Integer [diffList.size()];
		diffList.toArray(diffArr);
		return diffArr;
	}
	
	public static void main(String [] args){
		Integer [] a1 = new Integer []{1,5,8,10};
		Integer [] a2 = new Integer []{5,9,10};
		Integer [] temp = findDiff(a2,a1);
		for(Integer i: temp){
			System.out.println(i);
		}
	}
}
