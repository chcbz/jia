package cn.jia.task.job;

/**
 * 任务处理器接口
 * 所有需要被调度执行的任务都需要实现此接口
 * 
 * 使用方式：
 * 1. 实现此接口定义任务逻辑
 * 2. 通过 JobRegistry 注册到调度器
 * 3. 调度器会根据配置自动触发执行
 *
 * @author chc
 */
public interface JobHandler {

    /**
     * 获取任务处理器名称
     * 必须全局唯一，用于标识和查找处理器
     *
     * @return 处理器名称
     */
    String getName();

    /**
     * 执行任务
     * 当调度器触发任务时会调用此方法
     *
     * @param context 任务执行上下文，包含任务ID、参数等信息
     * @return 执行结果，null表示成功，否则返回错误信息
     * @throws Exception 执行过程中出现的异常
     */
    String execute(JobContext context) throws Exception;

    /**
     * 获取任务描述
     * 用于在管理界面显示任务的用途
     *
     * @return 任务描述
     */
    default String getDescription() {
        return "";
    }

    /**
     * 获取任务分组
     * 用于对任务进行分类管理
     *
     * @return 任务分组
     */
    default String getGroup() {
        return "DEFAULT";
    }

    /**
     * 获取cron表达式
     * 如果任务需要通过cron表达式调度，返回对应的表达式
     * 返回null表示不使用cron调度
     *
     * @return cron表达式，或null
     */
    default String getCron() {
        return null;
    }

    /**
     * 是否支持并行执行
     * 如果返回false，同一任务在执行时不会启动新的执行实例
     *
     * @return true-允许并行，false-不允许
     */
    default boolean isParallel() {
        return true;
    }
}