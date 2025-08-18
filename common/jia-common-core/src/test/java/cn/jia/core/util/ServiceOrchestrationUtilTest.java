package cn.jia.core.util;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;

public class ServiceOrchestrationUtilTest {

    // 用户实体类
    public static class User {
        private Integer id;
        private String name;
        private String department;
        private Integer age;

        public User() {}

        public User(Integer id, String name, String department, Integer age) {
            this.id = id;
            this.name = name;
            this.department = department;
            this.age = age;
        }

        // getter和setter方法
        public Integer getId() { return id; }
        public void setId(Integer id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDepartment() { return department; }
        public void setDepartment(String department) { this.department = department; }
        public Integer getAge() { return age; }
        public void setAge(Integer age) { this.age = age; }

        @Override
        public String toString() {
            return "User{" +
                    "id=" + id +
                    ", name='" + name + '\'' +
                    ", department='" + department + '\'' +
                    ", age=" + age +
                    '}';
        }
    }

    // 订单实体类
    public static class Order {
        private Integer id;
        private Integer userId;
        private String productName;
        private Double amount;

        public Order() {}

        public Order(Integer id, Integer userId, String productName, Double amount) {
            this.id = id;
            this.userId = userId;
            this.productName = productName;
            this.amount = amount;
        }

        // getter和setter方法
        public Integer getId() { return id; }
        public void setId(Integer id) { this.id = id; }
        public Integer getUserId() { return userId; }
        public void setUserId(Integer userId) { this.userId = userId; }
        public String getProductName() { return productName; }
        public void setProductName(String productName) { this.productName = productName; }
        public Double getAmount() { return amount; }
        public void setAmount(Double amount) { this.amount = amount; }

        @Override
        public String toString() {
            return "Order{" +
                    "id=" + id +
                    ", userId=" + userId +
                    ", productName='" + productName + '\'' +
                    ", amount=" + amount +
                    '}';
        }
    }

    // 用户订单组合类
    public static class UserOrder {
        private Integer userId;
        private String userName;
        private String department;
        private Integer age;
        private Integer orderId;
        private String productName;
        private Double amount;

        public UserOrder() {}

        public UserOrder(Object userObj, Object orderObj) {
            if (userObj instanceof User user) {
                this.userId = user.getId();
                this.userName = user.getName();
                this.department = user.getDepartment();
                this.age = user.getAge();
            }
            if (orderObj instanceof Order order) {
                this.orderId = order.getId();
                this.productName = order.getProductName();
                this.amount = order.getAmount();
            }
        }

        // getter和setter方法
        public Integer getUserId() { return userId; }
        public void setUserId(Integer userId) { this.userId = userId; }
        public String getUserName() { return userName; }
        public void setUserName(String userName) { this.userName = userName; }
        public String getDepartment() { return department; }
        public void setDepartment(String department) { this.department = department; }
        public Integer getAge() { return age; }
        public void setAge(Integer age) { this.age = age; }
        public Integer getOrderId() { return orderId; }
        public void setOrderId(Integer orderId) { this.orderId = orderId; }
        public String getProductName() { return productName; }
        public void setProductName(String productName) { this.productName = productName; }
        public Double getAmount() { return amount; }
        public void setAmount(Double amount) { this.amount = amount; }

        @Override
        public String toString() {
            return "UserOrder{" +
                    "userId=" + userId +
                    ", userName='" + userName + '\'' +
                    ", department='" + department + '\'' +
                    ", age=" + age +
                    ", orderId=" + orderId +
                    ", productName='" + productName + '\'' +
                    ", amount=" + amount +
                    '}';
        }
    }

    @Test
    public void testServiceOrchestration() throws Exception {
        // 模拟用户服务
        ServiceOrchestrationUtil.ServiceCall<List<User>> userService = () -> {
            Thread.sleep(100); // 模拟网络延迟
            return Arrays.asList(
                    new User(1, "张三", "技术部", 25),
                    new User(2, "李四", "销售部", 30),
                    new User(3, "王五", "技术部", 28),
                    new User(4, "赵六", "人事部", 35)
            );
        };

        // 模拟订单服务
        ServiceOrchestrationUtil.ServiceCall<List<Order>> orderService = () -> {
            Thread.sleep(150); // 模拟网络延迟
            return Arrays.asList(
                    new Order(1, 1, "笔记本电脑", 5000.0),
                    new Order(2, 2, "办公椅", 800.0),
                    new Order(3, 1, "鼠标", 100.0),
                    new Order(4, 3, "键盘", 200.0)
            );
        };

        // 创建服务配置
        List<ServiceOrchestrationUtil.ServiceConfig<Object>> serviceConfigs = Arrays.asList(
                new ServiceOrchestrationUtil.ServiceConfig<>("userService", userService::call),
                new ServiceOrchestrationUtil.ServiceConfig<>("orderService", orderService::call)
        );

        System.out.println("=== 并行调用多个原子服务并合并结果 ===");
        
        // 执行服务编排 - 使用并集聚合
        ServiceOrchestrationUtil.OrchestrationResult unionResult = 
                ServiceOrchestrationUtil.orchestrateServices(serviceConfigs, "union", null);
        
        System.out.println("服务调用结果:");
        unionResult.getServiceResults().forEach((name, result) -> 
                System.out.println(name + ": " + result));

        System.out.println("\n=== 使用MVEL表达式处理编排结果 ===");
        
        // 使用MVEL表达式处理结果 - 获取用户服务的结果
        Object userServiceResult = ServiceOrchestrationUtil.processResultWithExpression(
                unionResult, 
                "serviceResults.userService");
        System.out.println("用户服务结果: " + userServiceResult);

        // 使用MVEL表达式处理结果 - 获取订单服务的结果
        Object orderServiceResult = ServiceOrchestrationUtil.processResultWithExpression(
                unionResult, 
                "serviceResults.orderService");
        System.out.println("订单服务结果: " + orderServiceResult);

        // 连接用户和订单数据
        List<User> users = (List<User>) unionResult.getServiceResults().get("userService");
        List<Order> orders = (List<Order>) unionResult.getServiceResults().get("orderService");

        // 使用ArrayJoinUtil进行左连接
        List<UserOrder> userOrders = ArrayJoinUtil.leftJoin(
                users,
                orders,
                User::getId,
                Order::getUserId,
                UserOrder::new
        );

        System.out.println("\n=== 用户订单左连接结果 ===");
        userOrders.forEach(System.out::println);

        // 使用Java代码计算总金额，避免复杂的MVEL表达式
        double totalAmount = 0;
        for (UserOrder userOrder : userOrders) {
            if (userOrder.getAmount() != null) {
                totalAmount += userOrder.getAmount();
            }
        }
        System.out.println("\n订单总金额: " + totalAmount);

        // 按部门分组并计算每个部门的订单金额总和
        System.out.println("\n=== 按部门分组统计订单金额 ===");
        Map<String, Double> amountByDepartment = ArrayJoinUtil.groupBy(
                userOrders,
                UserOrder::getDepartment,
                list -> {
                    double sum = 0;
                    for (UserOrder uo : list) {
                        if (uo.getAmount() != null) {
                            sum += uo.getAmount();
                        }
                    }
                    return sum;
                }
        );
        amountByDepartment.forEach((dept, amount) -> 
                System.out.println(dept + ": " + amount));
    }
}