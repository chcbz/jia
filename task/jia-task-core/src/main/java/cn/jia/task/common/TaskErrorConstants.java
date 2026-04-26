package cn.jia.task.common;

import cn.jia.core.annotation.ErrorCodeModule;
import cn.jia.core.exception.EsErrorConstants;

/**
 * 任务错误常量
 * @author chc
 * @since 2017年12月8日 下午2:47:56
 */
@ErrorCodeModule("任务模块")
public class TaskErrorConstants extends EsErrorConstants {

    public TaskErrorConstants(String code, String message) {
        super(code, message);
    }
}