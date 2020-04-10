package cn.jia.isp.dao;

import cn.jia.isp.entity.IspSmbVDir;
import com.github.pagehelper.Page;

public interface IspSmbVDirMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(IspSmbVDir record);

    int insertSelective(IspSmbVDir record);

    IspSmbVDir selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(IspSmbVDir record);

    int updateByPrimaryKey(IspSmbVDir record);

    Page<IspSmbVDir> selectByExample(IspSmbVDir example);
}