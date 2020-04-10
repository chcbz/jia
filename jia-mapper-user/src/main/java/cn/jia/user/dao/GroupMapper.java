package cn.jia.user.dao;

import cn.jia.user.entity.Group;
import com.github.pagehelper.Page;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface GroupMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(Group record);

    int insertSelective(Group record);

    Group selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(Group record);

    int updateByPrimaryKey(Group record);
    
    Page<Group> selectAll(@Param("clientId") String clientId);
    
    List<Group> selectByUserId(@Param("userId") Integer userId, @Param("clientId") String clientId);
    
    Group selectByCode(String code);

    Page<Group> selectByExample(Group example);
}