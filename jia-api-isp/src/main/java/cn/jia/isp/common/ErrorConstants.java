package cn.jia.isp.common;

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
	/** 用户不存在 */
	public static final String USER_NOT_EXIST = "EUSER002";

	/** 服务器连接失败 */
	public static final String ISP_CONN_FAILD = "EISP001";
}
