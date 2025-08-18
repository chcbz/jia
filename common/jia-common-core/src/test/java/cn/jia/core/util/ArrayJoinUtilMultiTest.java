package cn.jia.core.util;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;

public class ArrayJoinUtilMultiTest {

    // 用户类
    public static class User {
        private Integer id;
        private String name;
        private Integer departmentId;
        private Integer roleId;

        public User() {}

        public User(Integer id, String name, Integer departmentId, Integer roleId) {
            this.id = id;
            this.name = name;
            this.departmentId = departmentId;
            this.roleId = roleId;
        }

        // getter和setter方法
        public Integer getId() { return id; }
        public void setId(Integer id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Integer getDepartmentId() { return departmentId; }
        public void setDepartmentId(Integer departmentId) { this.departmentId = departmentId; }
        public Integer getRoleId() { return roleId; }
        public void setRoleId(Integer roleId) { this.roleId = roleId; }

        @Override
        public String toString() {
            return "User{" +
                    "id=" + id +
                    ", name='" + name + '\'' +
                    ", departmentId=" + departmentId +
                    ", roleId=" + roleId +
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

    // 角色类
    public static class Role {
        private Integer id;
        private String name;

        public Role() {}

        public Role(Integer id, String name) {
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
            return "Role{" +
                    "id=" + id +
                    ", name='" + name + '\'' +
                    '}';
        }
    }

    // 连接结果类
    public static class UserDepartmentRole {
        private Integer userId;
        private String userName;
        private Integer departmentId;
        private String departmentName;
        private Integer roleId;
        private String roleName;

        public UserDepartmentRole() {}

        public UserDepartmentRole(List<Object> items) {
            User user = (User) items.get(0);
            Department department = (Department) items.get(1);
            Role role = (Role) items.get(2);

            this.userId = user != null ? user.getId() : null;
            this.userName = user != null ? user.getName() : null;
            this.departmentId = department != null ? department.getId() : null;
            this.departmentName = department != null ? department.getName() : null;
            this.roleId = role != null ? role.getId() : null;
            this.roleName = role != null ? role.getName() : null;
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
        public Integer getRoleId() { return roleId; }
        public void setRoleId(Integer roleId) { this.roleId = roleId; }
        public String getRoleName() { return roleName; }
        public void setRoleName(String roleName) { this.roleName = roleName; }

        @Override
        public String toString() {
            return "UserDepartmentRole{" +
                    "userId=" + userId +
                    ", userName='" + userName + '\'' +
                    ", departmentId=" + departmentId +
                    ", departmentName='" + departmentName + '\'' +
                    ", roleId=" + roleId +
                    ", roleName='" + roleName + '\'' +
                    '}';
        }
    }

    @Test
    public void testMultiJoinOperations() {
        // 准备测试数据
        List<User> users = Arrays.asList(
                new User(1, "张三", 1, 1),
                new User(2, "李四", 2, 2),
                new User(3, "王五", 1, 3),
                new User(4, "赵六", 3, 1),
                new User(5, "钱七", null, 2) // 没有部门的用户
        );

        List<Department> departments = Arrays.asList(
                new Department(1, "技术部"),
                new Department(2, "销售部"),
                new Department(3, "人事部"),
                new Department(4, "财务部") // 没有用户的部门
        );

        List<Role> roles = Arrays.asList(
                new Role(1, "管理员"),
                new Role(2, "普通用户"),
                new Role(3, "访客"),
                new Role(4, "审计员") // 没有用户的部门
        );

        System.out.println("用户列表:");
        users.forEach(System.out::println);
        System.out.println("\n部门列表:");
        departments.forEach(System.out::println);
        System.out.println("\n角色列表:");
        roles.forEach(System.out::println);

        // 测试多数组左连接 (LEFT JOIN)
        System.out.println("\n=== 多数组左连接 (LEFT JOIN) ===");
        List<UserDepartmentRole> multiLeftJoinResult = ArrayJoinUtil.multiLeftJoin(
                Arrays.asList(users, departments, roles),
                Arrays.asList(
                        (Function<User, Integer>) User::getId,
                        (Function<Department, Integer>) Department::getId,
                        (Function<Role, Integer>) Role::getId
                ),
                UserDepartmentRole::new
        );
        multiLeftJoinResult.forEach(System.out::println);

        // 测试多数组内连接 (INNER JOIN)
        System.out.println("\n=== 多数组内连接 (INNER JOIN) ===");
        List<UserDepartmentRole> multiInnerJoinResult = ArrayJoinUtil.multiInnerJoin(
                Arrays.asList(users, departments, roles),
                Arrays.asList(
                        (Function<User, Integer>) User::getDepartmentId,
                        (Function<Department, Integer>) Department::getId,
                        (Function<Role, Integer>) Role::getId
                ),
                items -> {
                    User user = (User) items.get(0);
                    Department department = (Department) items.get(1);
                    Role role = (Role) items.get(2);
                    return new UserDepartmentRole(Arrays.asList(user, department, role));
                }
        );
        multiInnerJoinResult.forEach(System.out::println);
    }
}