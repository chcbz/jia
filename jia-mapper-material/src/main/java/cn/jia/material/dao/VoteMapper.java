package cn.jia.material.dao;

import cn.jia.material.entity.Vote;
import com.github.pagehelper.Page;

public interface VoteMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(Vote record);

    int insertSelective(Vote record);

    Vote selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(Vote record);

    int updateByPrimaryKey(Vote record);
    
    Page<Vote> selectAll();
    
    Page<Vote> selectByExample(Vote example);
}