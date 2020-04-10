package cn.jia.user.dao;

import cn.jia.user.entity.Role;
import com.github.pagehelper.Page;
import org.apache.ibatis.annotations.Param;

public interface RoleMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(Role record);

    int insertSelective(Role record);

    Role selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(Role record);

    int updateByPrimaryKey(Role record);

    Page<Role> selectAll(@Param("clientId") String clientId);

    Page<Role> selectByUserId(@Param("userId") Integer userId, @Param("clientId") String clientId);

    Page<Role> selectByGroupId(@Param("groupId") Integer groupId, @Param("clientId") String clientId);

    Role selectByCode(String code);

    Page<Role> selectByExample(Role example);
}