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
        List<Map<String, Object>> resultData = targetData;
        if (orcheMeta.getJoins() != null) {
            for (OrcheEntity join : orcheMeta.getJoins()) {
                List<Map<String, Object>> joinData = data.get(join.getEntityName());
                if (joinData == null || joinData.isEmpty()) {
                    // 对于LEFT JOIN，即使右表为空，也要保留左表数据
                    if (LEFT_JOIN.equalsIgnoreCase(join.getJoinType())) {
                        resultData = handleLeftJoinWithEmptyRight(resultData, join);
                        continue;
                    }
                    // 对于其他连接类型，如果右表为空，则跳过
                    continue;
                }

                // 执行连接操作
                resultData = joinData(resultData, joinData, join);
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
                                               OrcheEntity join) {

        if (UNION_JOIN.equalsIgnoreCase(join.getJoinType())) {
            return handleUnionJoin(leftData, rightData);
        } else if (LEFT_JOIN.equalsIgnoreCase(join.getJoinType())) {
            return handleLeftJoin(leftData, rightData, join.getOperations(), join.getEntityName());
        } else {
            return handleDefaultJoin(leftData, rightData, join.getOperations(), join.getEntityName());
        }
    }

    /**
     * 处理UNION连接
     */
    private List<Map<String, Object>> handleUnionJoin(List<Map<String, Object>> leftData,
                                                      List<Map<String, Object>> rightData) {
        List<Map<String, Object>> result = new ArrayList<>(leftData.size() + rightData.size());

        // 添加左表数据
        result.addAll(leftData);

        // 添加右表数据
        result.addAll(rightData);

        return result;
    }

    /**
     * 处理LEFT JOIN连接
     */
    private List<Map<String, Object>> handleLeftJoin(List<Map<String, Object>> leftData,
                                                     List<Map<String, Object>> rightData,
                                                     List<OrcheOperation> operations,
                                                     String rightEntityName) {
        List<Map<String, Object>> result = new ArrayList<>();

        for (Map<String, Object> left : leftData) {
            boolean matched = false;

            for (Map<String, Object> right : rightData) {
                if (matchCondition(left, right, operations, rightEntityName)) {
                    matched = true;

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

            // 左连接时，即使没有匹配也要添加左表数据
            if (!matched) {
                Map<String, Object> joined = new HashMap<>(left);

                // 为右表字段添加null值
                if (!rightData.isEmpty()) {
                    Map<String, Object> sampleRight = rightData.get(0);
                    for (String key : sampleRight.keySet()) {
                        String prefixedKey = rightEntityName + DOT + key;
                        joined.put(prefixedKey, null);
                    }
                } else if (operations != null) {
                    // 如果右表为空，但需要为连接条件中的右表字段添加null值
                    for (OrcheOperation operation : operations) {
                        if (operation.getRight() != null && operation.getRight().getVariableName() != null) {
                            String rightField = operation.getRight().getVariableName();
                            if (rightField.contains(DOT) && rightField.startsWith(rightEntityName + DOT)) {
                                joined.put(rightField, null);
                            }
                        }
                        if (operation.getLeft() != null && operation.getLeft().getVariableName() != null) {
                            String leftField = operation.getLeft().getVariableName();
                            if (leftField.contains(DOT) && leftField.startsWith(rightEntityName + DOT)) {
                                joined.put(leftField, null);
                            }
                        }
                    }
                }

                result.add(joined);
            }
        }

        return result;
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
                                   List<OrcheOperation> operations, String rightTableName) {
        if (operations == null || operations.isEmpty()) {
            return true;
        }

        return evaluateOperations(leftData, rightData, operations, AND_COMBINE, rightTableName);
    }

    /**
     * 评估操作条件
     */
    private boolean evaluateOperations(Map<String, Object> leftData, Map<String, Object> rightData,
                                       List<OrcheOperation> operations, String combineType, String rightTableName) {
        boolean isAnd = AND_COMBINE.equalsIgnoreCase(combineType);
        boolean result = isAnd;

        for (OrcheOperation operation : operations) {
            boolean opResult = COMBINE_OPERATION.equals(operation.getOperationType()) ?
                    evaluateOperations(leftData, rightData, operation.getOperations(), operation.getCombineType(), rightTableName) :
                    evaluateCondition(leftData, rightData, operation, rightTableName);

            if (isAnd) {
                result = result && opResult;
                if (!result) break;
            } else {
                result = result || opResult;
                if (result) break;
            }
        }

        return result;
    }

    /**
     * 评估单个条件
     */
    private boolean evaluateCondition(Map<String, Object> leftData, Map<String, Object> rightData,
                                      OrcheOperation operation, String rightTableName) {
        if (operation.getLeft() == null || operation.getRight() == null) {
            return false;
        }

        String leftValue = getValue(leftData, rightData, operation.getLeft().getVariableName(), rightTableName);
        String rightValue = getValue(leftData, rightData, operation.getRight().getVariableName(), rightTableName);

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
    private String getValue(Map<String, Object> leftData, Map<String, Object> rightData, String expression, String rightTableName) {
        if (expression == null) {
            return null;
        }

        // 如果是字面值，直接返回
        if (!expression.contains(DOT)) {
            return expression;
        }

        // 解析表达式，获取表名和字段名
        String[] parts = expression.split("\\.");
        if (parts.length != 2) {
            // 如果表达式不止两部分，尝试作为字面值或简单字段处理
            return null;
        }
        String tableName = parts[0];
        String fieldName = parts[1];

        // 如果表名是右表名，则从右数据中获取字段值
        if (rightTableName.equals(tableName)) {
            Object value = rightData.get(fieldName);
            return value != null ? value.toString() : null;
        } else {
            // 否则从左数据中获取字段值
            // 先尝试用完整表达式获取（带前缀）
            Object value = leftData.get(expression);
            if (value == null) {
                // 再尝试用简单字段名获取（不带前缀）
                value = leftData.get(fieldName);
            }
            return value != null ? value.toString() : null;
        }
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
                        for (Map<String, Object> item : groupItems) {
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
                        resultMap.put(alias, sum);
                    }
                } else {
                    // 简单字段聚合
                    Object result = AggregationUtil.sum(groupItems, fieldExpr);
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