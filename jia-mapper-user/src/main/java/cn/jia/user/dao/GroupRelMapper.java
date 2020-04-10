package cn.jia.user.dao;

import cn.jia.user.entity.GroupRel;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface GroupRelMapper {
    int insert(GroupRel record);

    int insertSelective(GroupRel record);
    
    List<GroupRel> selectByUserId(Integer userId);
    
    List<GroupRel> selectByGroupId(Integer groupId);
    
    void batchAdd(@Param("groupRelList") List<GroupRel> groupRelList);
    
    void batchDel(@Param("groupRelList") List<GroupRel> groupRelList);
}