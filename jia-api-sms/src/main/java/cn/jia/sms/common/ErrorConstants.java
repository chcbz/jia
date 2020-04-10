package cn.jia.sms.common;

import cn.jia.core.exception.EsErrorConstants;

/**
 * 错误常量
 * @author chc
 * @date 2017年12月8日 下午2:47:56
 */
public class ErrorConstants extends EsErrorConstants {
	
	/** 可用短信不足 */
	public static final String SMS_NOT_ENOUGH = "ESMS001";
	/** 短信模板不存在 */
	public static final String SMS_TEMPLATE_NOT_EXIST = "ESMS002";
}
