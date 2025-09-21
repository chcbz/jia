package cn.jia.isp.service.impl;

import cn.jia.isp.entity.OrcheEntity;
import cn.jia.isp.entity.OrcheOperation;
import cn.jia.isp.entity.OrcheResultSet;
import cn.jia.isp.service.OrcheService;
import cn.jia.isp.service.util.AggregationUtil;
import org.mvel2.MVEL;
import org.mvel2.integration.VariableResolverFactory;
import org.mvel2.integration.impl.MapVariableResolverFactory;

import java.util.*;
import java.util.stream.Collectors;

public class OrcheServiceImpl implements OrcheService {

    // 常量定义
    private static final String UNION_JOIN = "union";
    private static final String LEFT_JOIN = "left";
    private static final String AND_COMBINE = "and";
    private static final String COMBINE_OPERATION = "combine";
    private static final String DOT = ".";
    private static final String PIPE = "|";

    @Override
    public List<Map<String, Object>> executeOrche(Map<String, List<Map<String, Object>>> data, OrcheEntity orcheMeta) {
        // 获取主数据集
        List<Map<String, Object>> targetData = data.get(orcheMeta.getEntityName());
        if (targetData == null || targetData.isEmpty()) {
            return Collections.emptyList();
        }

        // 处理连接操作
        List<Map<String, Object>> resultData = addEntityNamePrefix(targetData, orcheMeta.getEntityName());
        if (orcheMeta.getJoins() != null) {
            for (OrcheEntity join : orcheMeta.getJoins()) {
                List<Map<String, Object>> joinData;
                // 如果存在子连接，则进行连接操作
                if (join.getJoins() != null) {
                    joinData = executeOrche(data, join);
                } else {
                    joinData = addEntityNamePrefix(data.get(join.getEntityName()), join.getEntityName());
                }
                // 执行连接操作
                resultData = joinData(resultData, joinData, join, orcheMeta.getEntityName());
            }
        }

        // 处理分组和聚合操作
        if (orcheMeta.getGroupBys() != null && !orcheMeta.getGroupBys().isEmpty()) {
            resultData = groupAndAggregateData(resultData, orcheMeta.getGroupBys(), orcheMeta.getResultSets());
        } else if (orcheMeta.getResultSets() != null && !orcheMeta.getResultSets().isEmpty()) {
            // 处理非分组的结果集选择
            resultData = selectResultWithOrcheResultSet(resultData, orcheMeta.getResultSets());
        }

        return resultData;
    }

    private List<Map<String, Object>> addEntityNamePrefix(List<Map<String, Object>> targetData, String entityName) {
        List<Map<String, Object>> newData = new ArrayList<>();
        for (Map<String, Object> item : targetData) {
            Map<String, Object> joined = new HashMap<>();
            for (Map.Entry<String, Object> entry : item.entrySet()) {
                String leftKey = entry.getKey();
                if (leftKey.contains(DOT)) {
                    int index = leftKey.indexOf(DOT);
                    leftKey = leftKey.substring(index + 1);
                }
                leftKey = entityName + DOT + leftKey;
                joined.put(leftKey, entry.getValue());
            }
            newData.add(joined);
        }
        return newData;
    }

    /**
     * 处理右表为空的LEFT JOIN情况
     */
    private List<Map<String, Object>> handleLeftJoinWithEmptyRight(List<Map<String, Object>> leftData,
                                                                   OrcheEntity join) {
        List<Map<String, Object>> result = new ArrayList<>();
        String rightEntityName = join.getEntityName();

        for (Map<String, Object> leftItem : leftData) {

            // 添加左表数据
            Map<String, Object> joinedItem = new HashMap<>(leftItem);

            // 为右表字段添加null值
            if (join.getOperations() != null) {
                for (OrcheOperation operation : join.getOperations()) {
                    if (operation.getRight() != null && operation.getRight().getVariableName() != null) {
                        String rightField = operation.getRight().getVariableName();
                        if (rightField.contains(DOT) && rightField.startsWith(rightEntityName + DOT)) {
                            joinedItem.put(rightField, null);
                        }
                    }
                    if (operation.getLeft() != null && operation.getLeft().getVariableName() != null) {
                        String leftField = operation.getLeft().getVariableName();
                        if (leftField.contains(DOT) && leftField.startsWith(rightEntityName + DOT)) {
                            joinedItem.put(leftField, null);
                        }
                    }
                }
            }

            result.add(joinedItem);
        }

        return result;
    }

    /**
     * 执行数据连接操作
     */
    private List<Map<String, Object>> joinData(List<Map<String, Object>> leftData,
                                               List<Map<String, Object>> rightData,
                                               OrcheEntity join, String mainEntityName) {

        if (UNION_JOIN.equalsIgnoreCase(join.getJoinType())) {
            return handleUnionJoin(leftData, rightData, mainEntityName);
        } else if (LEFT_JOIN.equalsIgnoreCase(join.getJoinType())) {
            return handleLeftJoin(leftData, rightData, join.getOperations(), join.getCombineType());
        } else {
            return handleDefaultJoin(leftData, rightData, join.getOperations(), join.getEntityName());
        }
    }

    /**
     * 处理UNION连接
     */
    private List<Map<String, Object>> handleUnionJoin(List<Map<String, Object>> leftData,
                                                      List<Map<String, Object>> rightData, String mainEntityName) {
        List<Map<String, Object>> result = new ArrayList<>(leftData.size() + rightData.size());

        // 添加左表数据
        result.addAll(leftData);

        // 添加右表数据
        result.addAll(addEntityNamePrefix(rightData, mainEntityName));

        return result;
    }

    /**
     * 处理LEFT JOIN连接
     */
    private List<Map<String, Object>> handleLeftJoin(List<Map<String, Object>> leftData,
                                                     List<Map<String, Object>> rightData,
                                                     List<OrcheOperation> operations,
                                                     String combineType) {
        for (Map<String, Object> left : leftData) {
            for (Map<String, Object> right : rightData) {
                if (matchCondition(left, right, operations, combineType)) {
                    left.putAll(right);
                }
            }
        }

        return leftData;
    }

    /**
     * 处理默认连接（INNER JOIN）
     */
    private List<Map<String, Object>> handleDefaultJoin(List<Map<String, Object>> leftData,
                                                        List<Map<String, Object>> rightData,
                                                        List<OrcheOperation> operations,
                                                        String rightEntityName) {
        List<Map<String, Object>> result = new ArrayList<>();

        for (Map<String, Object> left : leftData) {
            for (Map<String, Object> right : rightData) {
                if (matchCondition(left, right, operations, rightEntityName)) {

                    // 添加左表数据
                    Map<String, Object> joined = new HashMap<>(left);

                    // 添加右表数据，使用右表实体名作为前缀
                    for (Map.Entry<String, Object> entry : right.entrySet()) {
                        String key = rightEntityName + DOT + entry.getKey();
                        joined.put(key, entry.getValue());
                    }

                    result.add(joined);
                }
            }
        }

        return result;
    }

    /**
     * 判断是否满足连接条件
     */
    private boolean matchCondition(Map<String, Object> leftData, Map<String, Object> rightData,
                                   List<OrcheOperation> operations, String combineType) {
        if (operations == null || operations.isEmpty()) {
            return true;
        }

        return evaluateOperations(leftData, rightData, operations, combineType);
    }

    /**
     * 评估操作条件
     */
    private boolean evaluateOperations(Map<String, Object> leftData, Map<String, Object> rightData,
                                       List<OrcheOperation> operations, String combineType) {
        boolean isAnd = AND_COMBINE.equalsIgnoreCase(combineType);
        boolean result = isAnd;

        for (OrcheOperation operation : operations) {
            result = COMBINE_OPERATION.equals(operation.getOperationType()) ?
                    evaluateOperations(leftData, rightData, operation.getOperations(), operation.getCombineType()) :
                    evaluateCondition(leftData, operation, rightData);
            if (isAnd) {
                if (!result) break;
            } else {
                if (result) break;
            }
        }

        return result;
    }

    /**
     * 评估单个条件
     */
    private boolean evaluateCondition(Map<String, Object> leftData,
                                      OrcheOperation operation, Map<String, Object> rightData) {
        if (operation.getLeft() == null || operation.getRight() == null) {
            return false;
        }

        String leftValue = getValue(leftData, operation.getLeft().getVariableName());
        String rightValue = getValue(rightData, operation.getRight().getVariableName());

        if (leftValue == null || rightValue == null) {
            return handleNullComparison(leftValue, rightValue, operation.getOperator());
        }

        return switch (operation.getOperator()) {
            case "=" -> Objects.equals(leftValue, rightValue);
            case "!=" -> !Objects.equals(leftValue, rightValue);
            case ">", ">=", "<", "<=" -> compareValues(leftValue, rightValue, operation.getOperator());
            default -> false;
        };
    }

    /**
     * 处理空值比较
     */
    private boolean handleNullComparison(String leftValue, String rightValue, String operator) {
        return switch (operator) {
            case "=" -> Objects.equals(leftValue, rightValue);
            case "!=" -> !Objects.equals(leftValue, rightValue);
            default -> false;
        };
    }

    /**
     * 从数据中获取值
     */
    private String getValue(Map<String, Object> data, String expression) {
        return String.valueOf(data.get(expression));
    }

    /**
     * 比较两个值
     */
    private boolean compareValues(String leftValue, String rightValue, String operator) {
        int comparison = compareValues(leftValue, rightValue);

        return switch (operator) {
            case ">" -> comparison > 0;
            case ">=" -> comparison >= 0;
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            default -> false;
        };
    }

    /**
     * 比较两个值返回整数结果
     */
    private int compareValues(String leftValue, String rightValue) {
        if (leftValue == null && rightValue == null) return 0;
        if (leftValue == null) return -1;
        if (rightValue == null) return 1;

        try {
            double leftNum = Double.parseDouble(leftValue);
            double rightNum = Double.parseDouble(rightValue);
            return Double.compare(leftNum, rightNum);
        } catch (NumberFormatException e) {
            return leftValue.compareTo(rightValue);
        }
    }

    /**
     * 对数据进行分组和聚合操作
     */
    private List<Map<String, Object>> groupAndAggregateData(List<Map<String, Object>> data,
                                                            List<String> groupBys,
                                                            List<OrcheResultSet> resultSets) {
        if (data.isEmpty() || groupBys == null || groupBys.isEmpty()) {
            return data;
        }

        // 按分组字段对数据进行分组
        Map<String, List<Map<String, Object>>> grouped = data.stream()
                .collect(Collectors.groupingBy(
                        item -> generateGroupKey(item, groupBys),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        // 对每组数据进行聚合
        return grouped.values().stream()
                .map(groupItems -> createGroupResult(groupItems, groupBys, resultSets))
                .collect(Collectors.toList());
    }

    /**
     * 创建分组结果
     */
    private Map<String, Object> createGroupResult(List<Map<String, Object>> groupItems,
                                                  List<String> groupBys,
                                                  List<OrcheResultSet> resultSets) {
        Map<String, Object> groupResult = new HashMap<>();

        if (!groupItems.isEmpty()) {
            Map<String, Object> firstItem = groupItems.get(0);

            // 添加分组字段
            for (String groupBy : groupBys) {
                Object value = findFieldValue(firstItem, groupBy);
                if (value != null) {
                    groupResult.put(groupBy, value);
                }
            }
        }

        // 处理聚合字段
        processAggregationFunctions(groupResult, groupItems, resultSets);

        return groupResult;
    }

    /**
     * 处理聚合函数
     */
    private void processAggregationFunctions(Map<String, Object> resultMap,
                                             List<Map<String, Object>> groupItems,
                                             List<OrcheResultSet> resultSets) {
        if (resultSets == null) return;

        for (OrcheResultSet resultSet : resultSets) {
            String expression = resultSet.getVariableName();
            String alias = resultSet.getAlias() != null ? resultSet.getAlias() : expression;

            if (expression != null && expression.startsWith("sum(") && expression.endsWith(")")) {
                String fieldExpr = expression.substring(4, expression.length() - 1);

                // 处理表达式聚合
                if (fieldExpr.contains("-")) {
                    String[] parts = fieldExpr.split("\\s*-\\s*");
                    if (parts.length == 2) {
                        double sum = 0;
                        boolean hasValue = false; // 标记是否有有效值
                        for (Map<String, Object> item : groupItems) {
                            Double leftValue = getNumericValue(item, parts[0].trim());
                            Double rightValue = getNumericValue(item, parts[1].trim());

                            if (leftValue != null && rightValue != null) {
                                sum += leftValue - rightValue;
                                hasValue = true;
                            } else if (leftValue != null) {
                                sum += leftValue;
                                hasValue = true;
                            } else if (rightValue != null) {
                                sum -= rightValue;
                                hasValue = true;
                            }
                        }
                        // 如果没有任何有效值，则返回0而不是null
                        resultMap.put(alias, hasValue ? sum : 0);
                    }
                } else {
                    // 简单字段聚合
                    Object result = AggregationUtil.sum(groupItems, fieldExpr);
                    // 如果聚合结果为null，则返回0
                    resultMap.put(alias, result != null ? result : 0);
                }
            } else if (expression != null && expression.startsWith("avg(") && expression.endsWith(")")) {
                String fieldName = expression.substring(4, expression.length() - 1);
                Object result = AggregationUtil.avg(groupItems, fieldName);
                resultMap.put(alias, result != null ? result : 0);
            } else if (expression != null && expression.startsWith("count(") && expression.endsWith(")")) {
                String fieldName = expression.substring(6, expression.length() - 1);
                Object result = AggregationUtil.count(groupItems, fieldName);
                resultMap.put(alias, result != null ? result : 0);
            } else if (expression != null && expression.startsWith("max(") && expression.endsWith(")")) {
                String fieldName = expression.substring(4, expression.length() - 1);
                Object result = AggregationUtil.max(groupItems, fieldName);
                resultMap.put(alias, result);
            } else if (expression != null && expression.startsWith("min(") && expression.endsWith(")")) {
                String fieldName = expression.substring(4, expression.length() - 1);
                Object result = AggregationUtil.min(groupItems, fieldName);
                resultMap.put(alias, result);
            } else {
                // 普通字段
                if (!groupItems.isEmpty()) {
                    Object value = findFieldValue(groupItems.get(0), expression);
                    resultMap.put(alias, value);
                }
            }
        }
    }

    /**
     * 生成分组键
     */
    private String generateGroupKey(Map<String, Object> item, List<String> groupBys) {
        return groupBys.stream()
                .map(groupBy -> {
                    Object value = findFieldValue(item, groupBy);
                    return value != null ? value.toString() : "null";
                })
                .collect(Collectors.joining(PIPE));
    }

    /**
     * 查找字段值
     */
    private Object findFieldValue(Map<String, Object> item, String fieldName) {
        // 先尝试直接查找
        if (item.containsKey(fieldName)) {
            return item.get(fieldName);
        }

        // 尝试查找带前缀的字段
        for (String key : item.keySet()) {
            if (key.endsWith(DOT + fieldName)) {
                return item.get(key);
            }
        }

        // 尝试查找不带前缀的字段
        if (fieldName.contains(DOT)) {
            String simpleFieldName = fieldName.substring(fieldName.indexOf(DOT) + 1);
            if (item.containsKey(simpleFieldName)) {
                return item.get(simpleFieldName);
            }
            // 尝试查找其他前缀的字段
            for (String key : item.keySet()) {
                if (key.endsWith(DOT + simpleFieldName)) {
                    return item.get(key);
                }
            }
        }

        return null;
    }

    /**
     * 从数据项中获取数值
     */
    private Double getNumericValue(Map<String, Object> item, String fieldName) {
        Object value = findFieldValue(item, fieldName);

        if (value != null) {
            try {
                // 处理字符串类型的数字
                if (value instanceof String) {
                    return Double.parseDouble((String) value);
                }
                return Double.parseDouble(value.toString());
            } catch (NumberFormatException e) {
                // 忽略无法转换为数字的值
            }
        }

        return null;
    }

    /**
     * 选择结果字段
     */
    private List<Map<String, Object>> selectResultWithOrcheResultSet(List<Map<String, Object>> data,
                                                                     List<OrcheResultSet> resultSets) {
        if (data.isEmpty() || resultSets == null || resultSets.isEmpty()) {
            return data;
        }

        // 预先计算聚合值
        Map<String, Object> aggregateCache = new HashMap<>();
        processAggregationFunctionsForCache(aggregateCache, data, resultSets);

        // 为每行数据构建结果
        return data.stream()
                .map(item -> buildResultItem(item, resultSets, aggregateCache))
                .collect(Collectors.toList());
    }

    /**
     * 构建结果项
     */
    private Map<String, Object> buildResultItem(Map<String, Object> item,
                                                List<OrcheResultSet> resultSets,
                                                Map<String, Object> aggregateCache) {
        Map<String, Object> newItem = new HashMap<>();

        for (OrcheResultSet resultSet : resultSets) {
            String expression = resultSet.getVariableName();
            String alias = resultSet.getAlias() != null ? resultSet.getAlias() : expression;

            if (isAggregateFunction(expression)) {
                // 处理聚合函数
                Object aggregateResult = aggregateCache.get(alias);
                newItem.put(alias, aggregateResult != null ? aggregateResult : 0);
            } else if (isExpression(expression)) {
                // 处理表达式计算
                try {
                    VariableResolverFactory factory = new MapVariableResolverFactory(item);
                    Object expressionResult = MVEL.eval(expression, factory);
                    newItem.put(alias, expressionResult);
                } catch (Exception e) {
                    newItem.put(alias, null);
                }
            } else {
                // 普通字段
                Object value = findFieldValue(item, expression);
                newItem.put(alias, value);
            }
        }

        return newItem;
    }

    /**
     * 判断是否为聚合函数
     */
    private boolean isAggregateFunction(String expression) {
        return expression != null && (
                expression.startsWith("sum(") && expression.endsWith(")") ||
                        expression.startsWith("avg(") && expression.endsWith(")") ||
                        expression.startsWith("count(") && expression.endsWith(")") ||
                        expression.startsWith("max(") && expression.endsWith(")") ||
                        expression.startsWith("min(") && expression.endsWith(")"));
    }

    /**
     * 判断是否为表达式
     */
    private boolean isExpression(String expression) {
        return expression != null &&
                (expression.contains(" + ") ||
                        expression.contains(" - ") ||
                        expression.contains(" * ") ||
                        expression.contains(" / "));
    }

    /**
     * 为缓存处理聚合函数
     */
    private void processAggregationFunctionsForCache(Map<String, Object> cache,
                                                     List<Map<String, Object>> data,
                                                     List<OrcheResultSet> resultSets) {
        if (resultSets == null) return;

        for (OrcheResultSet resultSet : resultSets) {
            String expression = resultSet.getVariableName();
            String alias = resultSet.getAlias() != null ? resultSet.getAlias() : expression;

            if (expression != null && expression.startsWith("sum(") && expression.endsWith(")")) {
                String fieldExpr = expression.substring(4, expression.length() - 1);

                // 处理表达式聚合
                if (fieldExpr.contains("-")) {
                    String[] parts = fieldExpr.split("\\s*-\\s*");
                    if (parts.length == 2) {
                        double sum = 0;
                        for (Map<String, Object> item : data) {
                            Double leftValue = getNumericValue(item, parts[0].trim());
                            Double rightValue = getNumericValue(item, parts[1].trim());

                            if (leftValue != null && rightValue != null) {
                                sum += leftValue - rightValue;
                            } else if (leftValue != null) {
                                sum += leftValue;
                            } else if (rightValue != null) {
                                sum -= rightValue;
                            }
                        }
                        cache.put(alias, sum);
                    }
                } else {
                    // 简单字段聚合
                    Object result = AggregationUtil.sum(data, fieldExpr);
                    cache.put(alias, result != null ? result : 0);
                }
            } else if (expression != null && expression.startsWith("avg(") && expression.endsWith(")")) {
                String fieldName = expression.substring(4, expression.length() - 1);
                Object result = AggregationUtil.avg(data, fieldName);
                cache.put(alias, result != null ? result : 0);
            } else if (expression != null && expression.startsWith("count(") && expression.endsWith(")")) {
                String fieldName = expression.substring(6, expression.length() - 1);
                Object result = AggregationUtil.count(data, fieldName);
                cache.put(alias, result != null ? result : 0);
            } else if (expression != null && expression.startsWith("max(") && expression.endsWith(")")) {
                String fieldName = expression.substring(4, expression.length() - 1);
                Object result = AggregationUtil.max(data, fieldName);
                cache.put(alias, result);
            } else if (expression != null && expression.startsWith("min(") && expression.endsWith(")")) {
                String fieldName = expression.substring(4, expression.length() - 1);
                Object result = AggregationUtil.min(data, fieldName);
                cache.put(alias, result);
            }
        }
    }
}