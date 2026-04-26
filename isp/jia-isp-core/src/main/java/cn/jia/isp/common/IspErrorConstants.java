package cn.jia.isp.common;

import cn.jia.core.annotation.ErrorCodeModule;
import cn.jia.core.exception.EsErrorConstants;

/**
 * 错误常量
 * @author chc
 * @since 2017年12月8日 下午2:47:56
 */
@ErrorCodeModule("ISP模块")
public class IspErrorConstants extends EsErrorConstants {
	public IspErrorConstants(String code, String message) {
		super(code, message);
	}
	
	/** 域名不存在 */
	public static final IspErrorConstants DOMAIN_NOT_EXIST = new IspErrorConstants("EOA001", "域名不存在");
	/** 密钥不正确 */
	public static final IspErrorConstants KEY_INCORRECT = new IspErrorConstants("EOA002", "密钥不正确");
	/** 用户不存在 */
	public static final IspErrorConstants USER_NOT_EXIST = new IspErrorConstants("EUSER002", "用户不存在");

	/** 服务器连接失败 */
	public static final IspErrorConstants ISP_CONN_FAILD = new IspErrorConstants("EISP001", "服务器连接失败");

}
