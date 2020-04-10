package cn.jia.material.common;

import cn.jia.core.exception.EsErrorConstants;

/**
 * 错误常量
 * @author chc
 * @date 2017年12月8日 下午2:47:56
 */
public class ErrorConstants extends EsErrorConstants {
	
	/** 用户不存在 */
	public static final String USER_NOT_EXIST = "EUSER002";
	/** 媒体类型不能为空 */
	public static final String MEDIA_TYPE_NEED = "EMAT001";
	/** 找不到相应的媒体资源 */
	public static final String MEDIA_NOT_EXIST = "EMAT002";
	/** 媒体标题不能为空 */
	public static final String MEDIA_TITLE_NEED = "EMAT003";
	/** 媒体内容不能为空 */
	public static final String MEDIA_CONTENT_NEED = "EMAT004";
}
