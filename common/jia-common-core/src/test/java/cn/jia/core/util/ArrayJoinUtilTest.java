package cn.jia.core.util;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;

public class ArrayJoinUtilTest {

    // 用户类
    public static class User {
        private Integer id;
        private String name;
        private Integer departmentId;

        public User() {
        }

        public User(Integer id, String name, Integer departmentId) {
            this.id = id;
            this.name = name;
            this.departmentId = departmentId;
        }

        // getter和setter方法
        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getDepartmentId() {
            return departmentId;
        }

        public void setDepartmentId(Integer departmentId) {
            this.departmentId = departmentId;
        }

        @Override
        public String toString() {
            return "User{" +
                    "id=" + id +
                    ", name='" + name + '\'' +
                    ", departmentId=" + departmentId +
                    '}';
        }
    }

    // 部门类
    public static class Department {
        private Integer id;
        private String name;

        public Department() {
        }

        public Department(Integer id, String name) {
            this.id = id;
            this.name = name;
        }

        // getter和setter方法
        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

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
        private String fullName; // 通过MVEL表达式计算的字段

        public UserDepartment() {
        }

        public UserDepartment(User user, Department department) {
            this.userId = user != null ? user.getId() : null;
            this.userName = user != null ? user.getName() : null;
            this.departmentId = department != null ? department.getId() : null;
            this.departmentName = department != null ? department.getName() : null;

            // 使用MVEL表达式计算全名
            Map<String, Object> vars = new HashMap<>();
            vars.put("userName", this.userName);
            vars.put("departmentName", this.departmentName);
            Object fullNameObj = ArrayJoinUtil.eval("userName != null && departmentName != null ? userName + ' (' + departmentName + ')' : (userName != null ? userName : departmentName)", vars);
            this.fullName = fullNameObj != null ? fullNameObj.toString() : null;
        }

        // getter和setter方法
        public Integer getUserId() {
            return userId;
        }

        public void setUserId(Integer userId) {
            this.userId = userId;
        }

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public Integer getDepartmentId() {
            return departmentId;
        }

        public void setDepartmentId(Integer departmentId) {
            this.departmentId = departmentId;
        }

        public String getDepartmentName() {
            return departmentName;
        }

        public void setDepartmentName(String departmentName) {
            this.departmentName = departmentName;
        }

        public String getFullName() {
            return fullName;
        }

        public void setFullName(String fullName) {
            this.fullName = fullName;
        }

        @Override
        public String toString() {
            return "UserDepartment{" +
                    "userId=" + userId +
                    ", userName='" + userName + '\'' +
                    ", departmentId=" + departmentId +
                    ", departmentName='" + departmentName + '\'' +
                    ", fullName='" + fullName + '\'' +
                    '}';
        }
    }

    @Test
    public void testJoinOperations() {
        // 准备测试数据
        List<User> users = Arrays.asList(
                new User(1, "张三", 1),
                new User(2, "李四", 2),
                new User(3, "王五", 1),
                new User(4, "赵六", 3),
                new User(5, "钱七", null) // 没有部门的用户
        );

        List<Department> departments = Arrays.asList(
                new Department(1, "技术部"),
                new Department(2, "销售部"),
                new Department(3, "人事部"),
                new Department(4, "财务部") // 没有用户的部门
        );

        System.out.println("用户列表:");
        users.forEach(System.out::println);
        System.out.println("\n部门列表:");
        departments.forEach(System.out::println);

        // 测试左连接 (LEFT JOIN)
        System.out.println("\n=== 左连接 (LEFT JOIN) ===");
        List<UserDepartment> leftJoinResult = ArrayJoinUtil.leftJoin(
                users,
                departments,
                User::getDepartmentId,
                Department::getId,
                UserDepartment::new
        );
        leftJoinResult.forEach(System.out::println);

        // 测试右连接 (RIGHT JOIN)
        System.out.println("\n=== 右连接 (RIGHT JOIN) ===");
        List<UserDepartment> rightJoinResult = ArrayJoinUtil.rightJoin(
                users,
                departments,
                (User u) -> u.getDepartmentId(),
                (Department d) -> d.getId(),
                (user, department) -> new UserDepartment(user, department)
        );
        rightJoinResult.forEach(System.out::println);

        // 测试内连接 (INNER JOIN)
        System.out.println("\n=== 内连接 (INNER JOIN) ===");
        List<UserDepartment> innerJoinResult = ArrayJoinUtil.innerJoin(
                users,
                departments,
                (User u) -> u.getDepartmentId(),
                (Department d) -> d.getId(),
                (user, department) -> new UserDepartment(user, department)
        );
        innerJoinResult.forEach(System.out::println);

        // 测试外连接 (FULL OUTER JOIN)
        System.out.println("\n=== 外连接 (FULL OUTER JOIN) ===");
        List<UserDepartment> outerJoinResult = ArrayJoinUtil.outerJoin(
                users,
                departments,
                (User u) -> u.getDepartmentId(),
                (Department d) -> d.getId(),
                (user, department) -> new UserDepartment(user, department)
        );
        outerJoinResult.forEach(System.out::println);

        // 演示使用MVEL表达式进行复杂计算
        System.out.println("\n=== MVEL表达式计算演示 ===");
        demonstrateMvelExpression();
    }

    private void demonstrateMvelExpression() {
        // 创建一些测试数据
        Map<String, Object> variables = new HashMap<>();
        variables.put("a", 10);
        variables.put("b", 5);
        variables.put("name", "测试用户");

        // 基本数学运算
        Object result1 = ArrayJoinUtil.eval("a + b * 2", variables);
        System.out.println("表达式 'a + b * 2' 的结果: " + result1);

        // 字符串操作
        Object result2 = ArrayJoinUtil.eval("name + ' ID: ' + (a+b)", variables);
        System.out.println("表达式 'name + \" ID: \" + (a+b)' 的结果: " + result2);

        // 条件表达式
        Object result3 = ArrayJoinUtil.eval("a > b ? 'a大于b' : 'a不大于b'", variables);
        System.out.println("条件表达式的结果: " + result3);

        // 使用指定类型
        String result4 = ArrayJoinUtil.eval("name + '-' + a", variables, String.class);
        System.out.println("指定返回类型为String: " + result4);
    }
}