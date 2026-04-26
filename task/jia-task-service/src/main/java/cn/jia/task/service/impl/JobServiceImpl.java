package cn.jia.task.service.impl;

import cn.jia.task.executor.JobExecutor;
import cn.jia.task.service.JobService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 任务调度服务实现
 *
 * @author chc
 */
@Service
public class JobServiceImpl implements JobService {

    @Autowired
    private JobExecutor jobExecutor;

    @Override
    public String execute(String jobName) {
        return jobExecutor.executeJob(jobName, null);
    }

    @Override
    public String execute(String jobName, Map<String, Object> params) {
        return jobExecutor.executeJob(jobName, params);
    }

    @Override
    public void executeAsync(String jobName, Map<String, Object> params) {
        jobExecutor.executeJobAsync(jobName, params);
    }

    @Override
    public boolean isRunning(String jobName) {
        return jobExecutor.isJobRunning(jobName);
    }
}