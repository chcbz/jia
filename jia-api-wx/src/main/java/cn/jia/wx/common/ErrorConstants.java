package cn.jia.wx.common;

import cn.jia.core.exception.EsErrorConstants;

/**
 * 错误常量
 * @author chc
 * @date 2017年12月8日 下午2:47:56
 */
public class ErrorConstants extends EsErrorConstants {
	
	/** 域名不存在 */
	public static final String DOMAIN_NOT_EXIST = "EOA001";
	/** 密钥不正确 */
	public static final String KEY_INCORRECT = "EOA002";
	
	public static final String POINT_SIGN_SUCCESS = "EOA002";
	
	/** 用户不存在 */
	public static final String USER_NOT_EXIST = "EUSER002";
	
	public static final Integer WX_ERRCODE_SUCCESS = 0;
	
	/** appid不能为空 */
	public static final String APPID_NOT_NULL = "EWX001";
	/** 找不到公众号 */
	public static final String WXMP_NOT_EXIST = "EWX002";
}
