package cn.jia.isp.dao;

import cn.jia.isp.entity.IspFile;
import com.github.pagehelper.Page;

public interface IspFileMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(IspFile record);

    int insertSelective(IspFile record);

    IspFile selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(IspFile record);

    int updateByPrimaryKey(IspFile record);

    IspFile selectByUri(String uri);

    Page<IspFile> selectByExample(IspFile example);
}