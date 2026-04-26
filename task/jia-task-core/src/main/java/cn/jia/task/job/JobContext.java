package cn.jia.task.job;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 任务执行上下文
 * 用于在任务执行时传递参数和上下文信息
 */
@Data
public class JobContext implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 任务实例ID
     */
    private String jobId;

    /**
     * 任务名称
     */
    private String jobName;

    /**
     * 任务分组
     */
    private String group;

    /**
     * 触发时间
     */
    private Long triggerTime;

    /**
     * 执行参数
     */
    private Map<String, Object> params;

    /**
     * 额外数据
     */
    private Object data;

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final JobContext context = new JobContext();

        public Builder jobId(String jobId) {
            context.setJobId(jobId);
            return this;
        }

        public Builder jobName(String jobName) {
            context.setJobName(jobName);
            return this;
        }

        public Builder group(String group) {
            context.setGroup(group);
            return this;
        }

        public Builder triggerTime(Long triggerTime) {
            context.setTriggerTime(triggerTime);
            return this;
        }

        public Builder params(Map<String, Object> params) {
            context.setParams(params);
            return this;
        }

        public Builder data(Object data) {
            context.setData(data);
            return this;
        }

        public JobContext build() {
            return context;
        }
    }
}