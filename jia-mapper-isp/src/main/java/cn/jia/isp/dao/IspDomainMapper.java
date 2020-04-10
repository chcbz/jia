package cn.jia.isp.dao;

import cn.jia.isp.entity.IspDomain;
import com.github.pagehelper.Page;

public interface IspDomainMapper {
    int deleteByPrimaryKey(Integer no);

    int insert(IspDomain record);

    int insertSelective(IspDomain record);

    IspDomain selectByPrimaryKey(Integer no);

    int updateByPrimaryKeySelective(IspDomain record);

    int updateByPrimaryKey(IspDomain record);

    Page<IspDomain> selectByExample(IspDomain example);
}