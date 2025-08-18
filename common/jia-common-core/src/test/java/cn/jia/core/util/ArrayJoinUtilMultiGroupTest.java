package cn.jia.core.util;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ArrayJoinUtilMultiGroupTest {

    // 用户类
    public static class User {
        private Integer id;
        private String name;
        private Integer departmentId;
        private Integer salary;

        public User() {}

        public User(Integer id, String name, Integer departmentId, Integer salary) {
            this.id = id;
            this.name = name;
            this.departmentId = departmentId;
            this.salary = salary;
        }

        // getter和setter方法
        public Integer getId() { return id; }
        public void setId(Integer id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Integer getDepartmentId() { return departmentId; }
        public void setDepartmentId(Integer departmentId) { this.departmentId = departmentId; }
        public Integer getSalary() { return salary; }
        public void setSalary(Integer salary) { this.salary = salary; }

        @Override
        public String toString() {
            return "User{" +
                    "id=" + id +
                    ", name='" + name + '\'' +
                    ", departmentId=" + departmentId +
                    ", salary=" + salary +
                    '}';
        }
    }

    // 部门类
    public static class Department {
        private Integer id;
        private String name;

        public Department() {}

        public Department(Integer id, String name) {
            this.id = id;
            this.name = name;
        }

        // getter和setter方法
        public Integer getId() { return id; }
        public void setId(Integer id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        @Override
        public String toString() {
            return "Department{" +
                    "id=" + id +
                    ", name='" + name + '\'' +
                    '}';
        }
    }

    // 连接结果类
    public static class UserDepartment {
        private Integer userId;
        private String userName;
        private Integer departmentId;
        private String departmentName;
        private Integer salary;

        public UserDepartment() {}

        public UserDepartment(List<Object> items) {
            User user = (User) items.get(0);
            Department department = (Department) items.get(1);

            this.userId = user != null ? user.getId() : null;
            this.userName = user != null ? user.getName() : null;
            this.departmentId = user != null ? user.getDepartmentId() : null;
            this.departmentName = department != null ? department.getName() : null;
            this.salary = user != null ? user.getSalary() : null;
        }

        // getter和setter方法
        public Integer getUserId() { return userId; }
        public void setUserId(Integer userId) { this.userId = userId; }
        public String getUserName() { return userName; }
        public void setUserName(String userName) { this.userName = userName; }
        public Integer getDepartmentId() { return departmentId; }
        public void setDepartmentId(Integer departmentId) { this.departmentId = departmentId; }
        public String getDepartmentName() { return departmentName; }
        public void setDepartmentName(String departmentName) { this.departmentName = departmentName; }
        public Integer getSalary() { return salary; }
        public void setSalary(Integer salary) { this.salary = salary; }

        @Override
        public String toString() {
            return "UserDepartment{" +
                    "userId=" + userId +
                    ", userName='" + userName + '\'' +
                    ", departmentId=" + departmentId +
                    ", departmentName='" + departmentName + '\'' +
                    ", salary=" + salary +
                    '}';
        }
    }

    @Test
    public void testMultiJoinWithGroupBy() {
        // 准备测试数据
        List<User> users = Arrays.asList(
                new User(1, "张三", 1, 5000),
                new User(2, "李四", 2, 6000),
                new User(3, "王五", 1, 5500),
                new User(4, "赵六", 3, 7000),
                new User(5, "钱七", 1, 5200),
                new User(6, "孙八", 2, 6200)
        );

        List<Department> departments = Arrays.asList(
                new Department(1, "技术部"),
                new Department(2, "销售部"),
                new Department(3, "人事部")
        );

        System.out.println("用户列表:");
        users.forEach(System.out::println);
        System.out.println("\n部门列表:");
        departments.forEach(System.out::println);

        // 多数组连接
        System.out.println("\n=== 多数组左连接 ===");
        List<UserDepartment> joinedResult = ArrayJoinUtil.multiLeftJoin(
                Arrays.asList(users, departments),
                Arrays.asList(
                        (Function<User, Integer>) User::getDepartmentId,
                        (Function<Department, Integer>) Department::getId
                ),
                UserDepartment::new
        );
        joinedResult.forEach(System.out::println);

        // 连接后按部门分组并计算各部门平均工资
        System.out.println("\n=== 按部门分组并计算平均工资 ===");
        Map<String, Double> avgSalaryByDepartment = ArrayJoinUtil.groupBy(
                joinedResult,
                UserDepartment::getDepartmentName,
                list -> list.stream()
                        .mapToInt(UserDepartment::getSalary)
                        .average()
                        .orElse(0.0)
        );
        avgSalaryByDepartment.forEach((department, avgSalary) ->
                System.out.println(department + ": 平均工资 " + String.format("%.2f", avgSalary))
        );

        // 连接后按部门分组并计算各部门工资总和
        System.out.println("\n=== 按部门分组并计算工资总和 ===");
        Map<String, Integer> totalSalaryByDepartment = ArrayJoinUtil.groupBy(
                joinedResult,
                UserDepartment::getDepartmentName,
                list -> list.stream()
                        .mapToInt(UserDepartment::getSalary)
                        .sum()
        );
        totalSalaryByDepartment.forEach((department, totalSalary) ->
                System.out.println(department + ": 工资总和 " + totalSalary)
        );

        // 连接后按部门分组并统计各部门人数
        System.out.println("\n=== 按部门分组并统计人数 ===");
        Map<String, Long> countByDepartment = joinedResult.stream()
                .collect(Collectors.groupingBy(
                        UserDepartment::getDepartmentName,
                        Collectors.counting()
                ));
        countByDepartment.forEach((department, count) ->
                System.out.println(department + ": " + count + "人")
        );

        // 连接后按部门分组并找出各部门最高工资
        System.out.println("\n=== 按部门分组并找出最高工资 ===");
        Map<String, Optional<UserDepartment>> maxSalaryByDepartment = joinedResult.stream()
                .collect(Collectors.groupingBy(
                        UserDepartment::getDepartmentName,
                        Collectors.maxBy(Comparator.comparing(UserDepartment::getSalary))
                ));
        maxSalaryByDepartment.forEach((department, userOpt) ->
                userOpt.ifPresent(user -> System.out.println(
                        department + ": 最高工资 " + user.getUserName() + " (" + user.getSalary() + ")"))
        );
    }
}