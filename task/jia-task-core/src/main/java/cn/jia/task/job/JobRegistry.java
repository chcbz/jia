package cn.jia.task.job;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务注册器
 * 负责管理所有任务处理器的注册和获取
 * <p>
 * 使用方式：
 * 1. 在应用启动时通过 Spring 扫描或手动注册 JobHandler
 * 2. 任务调度器通过此注册器获取处理器执行任务
 * 3. 各模块只需要实现 JobHandler 接口即可接入调度系统
 *
 * @author chc
 */
public class JobRegistry {

    /**
     * 任务处理器映射
     */
    private final Map<String, JobHandler> handlers = new ConcurrentHashMap<>();

    /**
     * 注册任务处理器
     *
     * @param handler 任务处理器
     */
    public void register(JobHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("JobHandler cannot be null");
        }
        String name = handler.getName();
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("JobHandler name cannot be empty");
        }
        handlers.put(name, handler);
    }

    /**
     * 注册任务处理器（带名称覆盖）
     *
     * @param name 处理器名称
     * @param handler 任务处理器
     */
    public void register(String name, JobHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("JobHandler cannot be null");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("JobHandler name cannot be empty");
        }
        handlers.put(name, handler);
    }

    /**
     * 获取任务处理器
     *
     * @param name 处理器名称
     * @return 任务处理器，如果不存在返回null
     */
    public JobHandler getHandler(String name) {
        return handlers.get(name);
    }

    /**
     * 获取所有处理器
     *
     * @return 所有已注册的处理器
     */
    public Map<String, JobHandler> getAllHandlers() {
        return new ConcurrentHashMap<>(handlers);
    }

    /**
     * 获取所有处理器数量
     *
     * @return 处理器数量
     */
    public int getHandlerCount() {
        return handlers.size();
    }

    /**
     * 检查处理器是否存在
     *
     * @param name 处理器名称
     * @return 是否存在
     */
    public boolean hasHandler(String name) {
        return handlers.containsKey(name);
    }

    /**
     * 移除任务处理器
     *
     * @param name 处理器名称
     * @return 被移除的处理器，如果不存在返回null
     */
    public JobHandler unregister(String name) {
        return handlers.remove(name);
    }

    /**
     * 清空所有处理器
     */
    public void clear() {
        handlers.clear();
    }

    /**
     * 获取指定分组的所有处理器
     *
     * @param group 分组名称
     * @return 该分组下的所有处理器
     */
    public Map<String, JobHandler> getHandlersByGroup(String group) {
        Map<String, JobHandler> result = new ConcurrentHashMap<>();
        handlers.forEach((name, handler) -> {
            if (group.equals(handler.getGroup())) {
                result.put(name, handler);
            }
        });
        return result;
    }
}