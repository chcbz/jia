package cn.jia.user.dao;

import cn.jia.user.entity.User;
import cn.jia.user.entity.UserExample;
import com.github.pagehelper.Page;

public interface UserMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(User record);

    int insertSelective(User record);

    User selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(User record);

    int updateByPrimaryKey(User record);

    Page<User> selectAll();

    User selectByJiacn(String jiacn);

    User selectByUsername(String username);

    User selectByOpenid(String openid);

    User selectByPhone(String phone);

    Page<User> selectByRole(Integer roleId);

    Page<User> selectByGroup(Integer groupId);

    Page<User> selectByOrg(Integer orgId);

    Page<User> searchByExample(User user);

    Page<User> selectByExample(UserExample user);
}