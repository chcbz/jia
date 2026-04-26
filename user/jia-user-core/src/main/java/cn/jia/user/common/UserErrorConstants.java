package cn.jia.user.common;

import cn.jia.core.annotation.ErrorCodeModule;
import cn.jia.core.exception.EsErrorConstants;

/**
 * 错误常量
 * @author chc
 * @since 2017年12月8日 下午2:47:56
 */
@ErrorCodeModule("用户模块")
public class UserErrorConstants extends EsErrorConstants {
	public UserErrorConstants(String code, String message) {
		super(code, message);
	}
	
	/** 用户积分不够 */
	public static final UserErrorConstants POINT_NO_ENOUGH = new UserErrorConstants("EUSER001", "用户积分不够");
	/** 用户不存在 */
	public static final UserErrorConstants USER_NOT_EXIST = new UserErrorConstants("EUSER002", "用户不存在");
	/** 用户已存在 */
	public static final UserErrorConstants USER_HAS_EXIST = new UserErrorConstants("EUSER003", "用户已存在");
	/** 旧密码不正确 */
	public static final UserErrorConstants OLD_PASSWORD_WRONG = new UserErrorConstants("EUSER004", "旧密码不正确");
	/** 角色不存在 */
	public static final UserErrorConstants ROLE_NOT_EXIST = new UserErrorConstants("EUSER005", "角色不存在");
	/** 找不到角色负责人 */
	public static final UserErrorConstants ORG_DIRECTOR_NOT_EXIST = new UserErrorConstants("EUSER006", "找不到角色负责人");
	/** 组织编码已存在 */
	public static final UserErrorConstants ORG_HAS_EXIST = new UserErrorConstants("EUSER007", "组织编码已存在");
	/** 手机号码已存在 */
	public static final UserErrorConstants USER_PHONE_HAS_EXIST = new UserErrorConstants("EUSER008", "手机号码已存在");
	/** OPENID不存在 */
	public static final UserErrorConstants OPENID_NOT_EXIST = new UserErrorConstants("EUSER009", "OPENID不存在");
	/** EMAIL不存在 */
	public static final UserErrorConstants EMAIL_NOT_EXIST = new UserErrorConstants("EUSER010", "EMAIL不存在");
	/** 电话号码不存在 */
	public static final UserErrorConstants PHONE_NOT_EXIST = new UserErrorConstants("EUSER011", "电话号码不存在");
	/** EMAIL已存在 */
	public static final UserErrorConstants USER_EMAIL_HAS_EXIST = new UserErrorConstants("EUSER012", "EMAIL已存在");

}
