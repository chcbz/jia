package cn.jia.isp.common;

import cn.jia.core.exception.EsErrorConstants;

/**
 * 错误常量
 * @author chc
 * @date 2017年12月8日 下午2:47:56
 */
public class ErrorConstants extends EsErrorConstants {
	
	/** 用户不存在 */
	public static final String USER_NOT_EXIST = "EUSER002";

	/** 服务器连接失败 */
	public static final String ISP_CONN_FAILD = "EISP001";

	/** 域名不存在 */
	public static final String DOMAIN_NOT_EXIST = "EISP002";
	/** 密钥不正确 */
	public static final String KEY_INCORRECT = "EISP003";
	/** SSL证书没有安装 */
	public static final String SSL_NOT_INSTALL = "EISP004";
	/** MYSQL服务没有安装 */
	public static final String MYSQL_NOT_INSTALL = "EISP005";
}
