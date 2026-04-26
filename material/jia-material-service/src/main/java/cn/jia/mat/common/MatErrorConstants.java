package cn.jia.mat.common;

import cn.jia.core.annotation.ErrorCodeModule;
import cn.jia.core.exception.EsErrorConstants;

/**
 * 错误常量
 *
 * @author chc
 * @since 2017年12月8日 下午2:47:56
 */
@ErrorCodeModule("素材模块")
public class MatErrorConstants extends EsErrorConstants {
    public MatErrorConstants(String code, String message) {
        super(code, message);
    }

    /**
     * 媒体类型不能为空
     */
    public static final MatErrorConstants MEDIA_TYPE_NEED = new MatErrorConstants("EMAT001", "媒体类型不能为空");
    /**
     * 找不到相应的媒体资源
     */
    public static final MatErrorConstants MEDIA_NOT_EXIST = new MatErrorConstants("EMAT002", "找不到相应的媒体资源");
    /**
     * 媒体标题不能为空
     */
    public static final MatErrorConstants MEDIA_TITLE_NEED = new MatErrorConstants("EMAT003", "媒体标题不能为空");
    /**
     * 媒体内容不能为空
     */
    public static final MatErrorConstants MEDIA_CONTENT_NEED = new MatErrorConstants("EMAT004", "媒体内容不能为空");

    /**
     * 存在相似短语
     */
    public static final MatErrorConstants PHRASE_HAS_EXIST = new MatErrorConstants("EMAT005", "存在相似短语");

    /**
     * ES服务异常
     */
    public static final MatErrorConstants ES_SERVICE_ERROR = new MatErrorConstants("EMAT006", "ES服务异常");
}