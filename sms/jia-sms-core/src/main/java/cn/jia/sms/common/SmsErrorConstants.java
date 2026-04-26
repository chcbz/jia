package cn.jia.sms.common;

import cn.jia.core.annotation.ErrorCodeModule;
import cn.jia.core.exception.EsErrorConstants;

/**
 * 错误常量
 * @author chc
 * @since 2017年12月8日 下午2:47:56
 */
@ErrorCodeModule("短信模块")
public class SmsErrorConstants extends EsErrorConstants {
	public SmsErrorConstants(String code, String message) {
		super(code, message);
	}

	/** 可用短信不足 */
	public static final SmsErrorConstants SMS_NOT_ENOUGH = new SmsErrorConstants("ESMS001", "可用短信不足");
	/** 短信模板不存在 */
	public static final SmsErrorConstants SMS_TEMPLATE_NOT_EXIST = new SmsErrorConstants("ESMS002", "短信模板不存在");

	public static final SmsErrorConstants SMS_CODE_INCORRECT = new SmsErrorConstants("SmsErrorConstants", "短信验证码不正确");
}