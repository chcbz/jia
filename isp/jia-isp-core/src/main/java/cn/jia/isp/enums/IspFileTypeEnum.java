package cn.jia.isp.enums;

import lombok.Getter;

/**
 * ISP文件类型枚举
 *
 * @author chc
 */
@Getter
public enum IspFileTypeEnum {
    /** 文件类型-LOGO */
    FILE_TYPE_LOGO(1, "logo"),
    /** 文件类型-用户头像 */
    FILE_TYPE_AVATAR(2, "avatar"),
    /** 文件类型-公告内容图片 */
    FILE_TYPE_NOTICE(3, "notice"),
    /** 文件类型-自定义表单图片 */
    FILE_TYPE_CMS(4, "cms"),
    /** 文件类型-图文素材 */
    FILE_TYPE_MAT(5, "mat");

    /**
     *  获取类型编码
     */
    private final Integer code;
    /**
     *  获取类型名称
     */
    private final String name;

    IspFileTypeEnum(Integer code, String name) {
        this.code = code;
        this.name = name;
    }
}
