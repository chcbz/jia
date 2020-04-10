package cn.jia.user.dao;

import cn.jia.user.entity.RoleRel;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface RoleRelMapper {
    int insert(RoleRel record);

    int insertSelective(RoleRel record);
    
    List<RoleRel> selectByUserId(@Param("userId") Integer userId, @Param("clientId") String clientId);

    List<RoleRel> selectByGroupId(@Param("groupId") Integer groupId, @Param("clientId") String clientId);
    
    List<RoleRel> selectByRoleId(@Param("roleId") Integer roleId, @Param("clientId") String clientId);
    
    void batchAdd(@Param("roleRelList") List<RoleRel> roleRelList);
    
    void batchDel(@Param("roleRelList") List<RoleRel> roleRelList);
}