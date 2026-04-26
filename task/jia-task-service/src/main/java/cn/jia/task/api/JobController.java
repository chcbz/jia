package cn.jia.task.api;

import cn.jia.core.entity.JsonResult;
import cn.jia.task.executor.JobExecutor;
import cn.jia.task.job.JobHandler;
import cn.jia.task.job.JobRegistry;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 任务调度接口
 * 提供给其他模块调用的REST API
 *
 * @author chc
 */
@RestController
@RequestMapping("/job")
public class JobController {

    @Autowired
    private JobRegistry jobRegistry;

    @Autowired
    private JobExecutor jobExecutor;

    /**
     * 获取所有已注册的任务处理器
     *
     * @return 任务处理器列表
     */
    @PreAuthorize("hasAuthority('job-list')")
    @GetMapping("/list")
    public Object list() {
        Map<String, JobHandler> handlers = jobRegistry.getAllHandlers();
        return JsonResult.success(handlers);
    }

    /**
     * 获取任务处理器详情
     *
     * @param name 处理器名称
     * @return 处理器信息
     */
    @PreAuthorize("hasAuthority('job-get')")
    @GetMapping("/get")
    public Object get(@RequestParam String name) {
        JobHandler handler = jobRegistry.getHandler(name);
        if (handler == null) {
            return JsonResult.failure("404", "Job handler not found: " + name);
        }
        Map<String, Object> info = new HashMap<>();
        info.put("name", handler.getName());
        info.put("description", handler.getDescription());
        info.put("group", handler.getGroup());
        info.put("cron", handler.getCron());
        info.put("parallel", handler.isParallel());
        return JsonResult.success(info);
    }

    /**
     * 执行任务
     *
     * @param request 执行请求
     * @return 执行结果
     */
    @PreAuthorize("hasAuthority('job-execute')")
    @PostMapping("/execute")
    public Object execute(@RequestBody JobExecuteRequest request) {
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            return JsonResult.failure("400", "Job name is required");
        }
        String result = jobExecutor.executeJob(request.getName(), request.getParams());
        return JsonResult.success(result);
    }

    /**
     * 异步执行任务
     *
     * @param request 执行请求
     * @return 成功信息
     */
    @PreAuthorize("hasAuthority('job-execute')")
    @PostMapping("/execute/async")
    public Object executeAsync(@RequestBody JobExecuteRequest request) {
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            return JsonResult.failure("400", "Job name is required");
        }
        jobExecutor.executeJobAsync(request.getName(), request.getParams());
        return JsonResult.success("Job submitted for async execution");
    }

    /**
     * 检查任务是否正在运行
     *
     * @param name 处理器名称
     * @return 运行状态
     */
    @PreAuthorize("hasAuthority('job-status')")
    @GetMapping("/status")
    public Object status(@RequestParam String name) {
        boolean running = jobExecutor.isJobRunning(name);
        Map<String, Object> result = new HashMap<>();
        result.put("name", name);
        result.put("running", running);
        return JsonResult.success(result);
    }

    /**
     * 执行请求
     */
    @Data
    public static class JobExecuteRequest {
        /**
         * 任务名称
         */
        private String name;

        /**
         * 执行参数
         */
        private Map<String, Object> params;
    }
}