package cn.jia.workflow.listener;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;

import java.util.Map;

/**
 * 开启SKIP_EXPRESSION功能
 * @author chcbz
 * @date 2018年9月11日 下午3:37:31
 */
public class SkipListener implements ExecutionListener {

    @Override
    public void notify(DelegateExecution execution) {
        // 获取流程变量
        Map<String, Object> variables = execution.getVariables();
        // 开启支持跳过表达式 ActivitiConstanct.getSkipExpression()就是"_ACTIVITI_SKIP_EXPRESSION_ENABLED"
        variables.put("_ACTIVITI_SKIP_EXPRESSION_ENABLED", true);
        // 将修改同步到流程中
        execution.setVariables(variables);
        // 这种方式也行。直接设置流程变量
        // execution.setVariable(ActivitiConstanct.getSkipExpression(),true);
    }
}
