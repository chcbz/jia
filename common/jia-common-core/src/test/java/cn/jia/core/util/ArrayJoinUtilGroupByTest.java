package cn.jia.core.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ArrayJoinUtilGroupByTest {

    // 用户类
    public static class User {
        private Integer id;
        private String name;
        private Integer age;
        private String department;

        public User() {}

        public User(Integer id, String name, Integer age, String department) {
            this.id = id;
            this.name = name;
            this.age = age;
            this.department = department;
        }

        // getter和setter方法
        public Integer getId() { return id; }
        public void setId(Integer id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Integer getAge() { return age; }
        public void setAge(Integer age) { this.age = age; }
        public String getDepartment() { return department; }
        public void setDepartment(String department) { this.department = department; }

        @Override
        public String toString() {
            return "User{" +
                    "id=" + id +
                    ", name='" + name + '\'' +
                    ", age=" + age +
                    ", department='" + department + '\'' +
                    '}';
        }
    }

    @Test
    public void testGroupBy() {
        // 准备测试数据
        List<User> users = Arrays.asList(
                new User(1, "张三", 25, "技术部"),
                new User(2, "李四", 30, "销售部"),
                new User(3, "王五", 28, "技术部"),
                new User(4, "赵六", 35, "人事部"),
                new User(5, "钱七", 26, "技术部"),
                new User(6, "孙八", 32, "销售部")
        );

        System.out.println("用户列表:");
        users.forEach(System.out::println);

        // 测试基本的GROUP BY操作 - 按部门分组
        System.out.println("\n=== 按部门分组 ===");
        Map<String, List<User>> groupedByDepartment = ArrayJoinUtil.groupBy(users, User::getDepartment);
        groupedByDepartment.forEach((department, userList) -> {
            System.out.println(department + ":");
            userList.forEach(user -> System.out.println("  " + user));
        });

        // 测试带收集器的GROUP BY操作 - 按部门分组并计算每个部门的平均年龄
        System.out.println("\n=== 按部门分组并计算平均年龄 ===");
        Map<String, Double> avgAgeByDepartment = ArrayJoinUtil.groupBy(
                users, 
                User::getDepartment, 
                userList -> userList.stream().mapToInt(User::getAge).average().orElse(0.0)
        );
        avgAgeByDepartment.forEach((department, avgAge) -> 
            System.out.println(department + ": 平均年龄 " + String.format("%.2f", avgAge))
        );

        // 测试带收集器的GROUP BY操作 - 按部门分组并统计每个部门的人数
        System.out.println("\n=== 按部门分组并统计人数 ===");
        Map<String, Integer> countByDepartment = ArrayJoinUtil.groupBy(
                users,
                User::getDepartment,
                List::size
        );
        countByDepartment.forEach((department, count) ->
                System.out.println(department + ": " + count + "人")
        );

        // 测试按年龄范围分组
        System.out.println("\n=== 按年龄范围分组 ===");
        Map<String, List<User>> groupedByAgeRange = ArrayJoinUtil.groupBy(users, user -> {
            if (user.getAge() < 25) {
                return "25岁以下";
            } else if (user.getAge() < 30) {
                return "25-29岁";
            } else if (user.getAge() < 35) {
                return "30-34岁";
            } else {
                return "35岁及以上";
            }
        });
        groupedByAgeRange.forEach((ageRange, userList) -> {
            System.out.println(ageRange + ":");
            userList.forEach(user -> System.out.println("  " + user));
        });
    }
}