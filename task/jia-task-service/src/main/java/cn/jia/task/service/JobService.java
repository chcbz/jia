package cn.jia.task.service;

import java.util.Map;

/**
 * 任务调度服务接口
 * 提供给其他模块使用的服务接口
 *
 * @author chc
 */
public interface JobService {

    /**
     * 执行指定名称的任务
     *
     * @param jobName 任务名称
     * @return 执行结果，null表示成功，否则返回错误信息
     */
    String execute(String jobName);

    /**
     * 执行指定名称的任务（带参数）
     *
     * @param jobName 任务名称
     * @param params 执行参数
     * @return 执行结果，null表示成功，否则返回错误信息
     */
    String execute(String jobName, Map<String, Object> params);

    /**
     * 异步执行指定名称的任务
     *
     * @param jobName 任务名称
     * @param params 执行参数
     */
    void executeAsync(String jobName, Map<String, Object> params);

    /**
     * 检查任务是否正在运行
     *
     * @param jobName 任务名称
     * @return true-正在运行，false-未运行
     */
    boolean isRunning(String jobName);
}