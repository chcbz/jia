package cn.jia.isp.dao;

import cn.jia.isp.entity.IspServer;
import com.github.pagehelper.Page;

public interface IspServerMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(IspServer record);

    int insertSelective(IspServer record);

    IspServer selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(IspServer record);

    int updateByPrimaryKey(IspServer record);

    Page<IspServer> selectByExample(IspServer example);
}