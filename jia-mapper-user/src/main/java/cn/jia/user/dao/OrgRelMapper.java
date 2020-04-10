package cn.jia.user.dao;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import cn.jia.user.entity.OrgRel;

public interface OrgRelMapper {
    int insert(OrgRel record);

    int insertSelective(OrgRel record);
    
    List<OrgRel> selectByUserId(Integer userId);
    
    List<OrgRel> selectByOrgId(Integer orgId);
    
    void batchAdd(@Param("orgRelList") List<OrgRel> orgRelList);
    
    void batchDel(@Param("orgRelList") List<OrgRel> orgRelList);
}