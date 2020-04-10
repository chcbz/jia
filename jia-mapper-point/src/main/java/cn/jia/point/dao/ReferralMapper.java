package cn.jia.point.dao;

import cn.jia.point.entity.Referral;

public interface ReferralMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(Referral record);

    int insertSelective(Referral record);

    Referral selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(Referral record);

    int updateByPrimaryKey(Referral record);
    
    Referral selectByReferral(String referral);
}