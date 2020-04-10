package cn.jia.core.dao;

import cn.jia.core.entity.Dict;
import com.github.pagehelper.Page;

import java.util.List;

public interface DictMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(Dict record);

    int insertSelective(Dict record);

    Dict selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(Dict record);

    int updateByPrimaryKey(Dict record);
    
    List<Dict> selectAll();
    
    Page<Dict> findByExamplePage(Dict record);
    
    int upsert(Dict record);
}