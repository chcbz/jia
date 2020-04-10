package cn.jia.user.common;

import cn.jia.core.exception.EsErrorConstants;

/**
 * 错误常量
 * @author chc
 * @date 2017年12月8日 下午2:47:56
 */
public class ErrorConstants extends EsErrorConstants {
	
	/** 用户积分不够 */
	public static final String POINT_NO_ENOUGH = "EUSER001";
	/** 用户不存在 */
	public static final String USER_NOT_EXIST = "EUSER002";
	/** 用户已存在 */
	public static final String USER_HAS_EXIST = "EUSER003";
	/** 旧密码不正确 */
	public static final String OLD_PASSWORD_WRONG = "EUSER004";
	/** 角色不存在 */
	public static final String ROLE_NOT_EXIST = "EUSER005";
	/** 找不到角色负责人 */
	public static final String ORG_DIRECTOR_NOT_EXIST = "EUSER006";
	/** 组织编码已存在 */
	public static final String ORG_HAS_EXIST = "EUSER007";
	/** 手机号码已存在 */
	public static final String USER_PHONE_HAS_EXIST = "EUSER008";
	/** OPENID不存在 */
	public static final String OPENID_NOT_EXIST = "EUSER009";
	/** EMAIL不存在 */
	public static final String EMAIL_NOT_EXIST = "EUSER010";
	/** 电话号码不存在 */
	public static final String PHONE_NOT_EXIST = "EUSER011";
}
