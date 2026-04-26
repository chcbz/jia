package cn.jia.dwz.common;

import cn.jia.core.annotation.ErrorCodeModule;
import cn.jia.core.exception.EsErrorConstants;

/**
 * 错误常量
 * @author chc
 * @since 2017年12月8日 下午2:47:56
 */
@ErrorCodeModule("短链接模块")
public class DwzErrorConstants extends EsErrorConstants {
	public DwzErrorConstants(String code, String message) {
		super(code, message);
	}
	
	/** 用户积分不够 */
	public static final DwzErrorConstants POINT_NO_ENOUGH = new DwzErrorConstants("EUSER001", "用户积分不够");
	/** 用户不存在 */
	public static final DwzErrorConstants USER_NOT_EXIST = new DwzErrorConstants("EUSER002", "用户不存在");
	/** 用户已存在 */
	public static final DwzErrorConstants USER_HAS_EXIST = new DwzErrorConstants("EUSER003", "用户已存在");
	/** 旧密码不正确 */
	public static final DwzErrorConstants OLD_PASSWORD_WRONG = new DwzErrorConstants("EUSER004", "旧密码不正确");
	/** 角色不存在 */
	public static final DwzErrorConstants ROLE_NOT_EXIST = new DwzErrorConstants("EUSER005", "角色不存在");
	/** 找不到角色负责人 */
	public static final DwzErrorConstants ORG_DIRECTOR_NOT_EXIST = new DwzErrorConstants("EUSER006", "找不到角色负责人");

}