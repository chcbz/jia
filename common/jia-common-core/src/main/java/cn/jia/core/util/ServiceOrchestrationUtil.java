package cn.jia.core.util;

import org.mvel2.MVEL;
import org.mvel2.integration.VariableResolverFactory;
import org.mvel2.integration.impl.MapVariableResolverFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 服务编排工具类
 * 支持调用多个原子服务，合并结果，并根据MVEL表达式配置生成最终结果
 */
public class ServiceOrchestrationUtil {

    /**
     * 服务调用函数接口
     *
     * @param <T> 服务返回结果类型
     */
    @FunctionalInterface
    public interface ServiceCall<T> {
        T call() throws Exception;
    }

    /**
     * 服务编排配置
     *
     * @param <T> 服务返回结果类型
     */
    public static class ServiceConfig<T> {
        private String name;
        private ServiceCall<T> serviceCall;

        public ServiceConfig(String name, ServiceCall<T> serviceCall) {
            this.name = name;
            this.serviceCall = serviceCall;
        }

        public String getName() {
            return name;
        }

        public ServiceCall<T> getServiceCall() {
            return serviceCall;
        }
    }

    /**
     * 编排结果
     */
    public static class OrchestrationResult {
        private Map<String, Object> serviceResults;
        private Object finalResult;

        public OrchestrationResult() {
            this.serviceResults = new HashMap<>();
        }

        public void addServiceResult(String serviceName, Object result) {
            serviceResults.put(serviceName, result);
        }

        public Map<String, Object> getServiceResults() {
            return serviceResults;
        }

        public Object getFinalResult() {
            return finalResult;
        }

        public void setFinalResult(Object finalResult) {
            this.finalResult = finalResult;
        }
    }

    /**
     * 并行调用多个原子服务并合并结果
     *
     * @param serviceConfigs 服务配置列表
     * @param aggregatorType 聚合类型: "union"(并集), "groupBy"(分组聚合)
     * @param groupKeyFunc   分组键函数（仅在groupBy模式下使用）
     * @param <T>            服务返回结果类型
     * @return 编排结果
     */
    public static <T> OrchestrationResult orchestrateServices(
            List<ServiceConfig<T>> serviceConfigs,
            String aggregatorType,
            Function<T, Object> groupKeyFunc) {
        
        OrchestrationResult result = new OrchestrationResult();
        
        // 创建线程池执行服务调用
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(serviceConfigs.size(), 10));
        
        try {
            // 并行调用所有服务
            List<CompletableFuture<Map.Entry<String, Object>>> futures = new ArrayList<>();
            for (ServiceConfig<T> config : serviceConfigs) {
                CompletableFuture<Map.Entry<String, Object>> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        T serviceResult = config.getServiceCall().call();
                        return new AbstractMap.SimpleEntry<String, Object>(config.getName(), serviceResult);
                    } catch (Exception e) {
                        throw new RuntimeException("Service call failed: " + config.getName(), e);
                    }
                }, executor);
                futures.add(future);
            }
            
            // 等待所有服务调用完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
            // 收集服务结果
            List<T> allResults = new ArrayList<>();
            for (CompletableFuture<Map.Entry<String, Object>> future : futures) {
                try {
                    Map.Entry<String, Object> entry = future.get();
                    result.addServiceResult(entry.getKey(), entry.getValue());
                    if (entry.getValue() instanceof List) {
                        allResults.addAll((List<T>) entry.getValue());
                    } else {
                        allResults.add((T) entry.getValue());
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed to get service result", e);
                }
            }
            
            // 根据聚合类型合并结果
            Object mergedResult;
            switch (aggregatorType) {
                case "union":
                    mergedResult = allResults;
                    break;
                case "groupBy":
                    if (groupKeyFunc != null) {
                        mergedResult = ArrayJoinUtil.groupBy(allResults, (Function<T, Object>) groupKeyFunc);
                    } else {
                        mergedResult = allResults;
                    }
                    break;
                default:
                    mergedResult = allResults;
            }
            
            result.setFinalResult(mergedResult);
            return result;
        } finally {
            executor.shutdown();
        }
    }

    /**
     * 使用MVEL表达式处理编排结果
     *
     * @param result           编排结果
     * @param resultExpression MVEL表达式
     * @return 表达式计算结果
     */
    public static Object processResultWithExpression(OrchestrationResult result, String resultExpression) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("serviceResults", result.getServiceResults());
        variables.put("finalResult", result.getFinalResult());
        
        VariableResolverFactory factory = new MapVariableResolverFactory(variables);
        return MVEL.eval(resultExpression, factory);
    }

    /**
     * 使用MVEL表达式处理编排结果并转换为指定类型
     *
     * @param result           编排结果
     * @param resultExpression MVEL表达式
     * @param toType           目标类型
     * @param <R>              结果类型
     * @return 表达式计算结果
     */
    public static <R> R processResultWithExpression(OrchestrationResult result, String resultExpression, Class<R> toType) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("serviceResults", result.getServiceResults());
        variables.put("finalResult", result.getFinalResult());
        
        VariableResolverFactory factory = new MapVariableResolverFactory(variables);
        return MVEL.eval(resultExpression, factory, toType);
    }
}