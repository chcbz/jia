package cn.jia.wx.dao;

import cn.jia.wx.entity.MpUser;
import com.github.pagehelper.Page;

public interface MpUserMapper {

    int deleteByPrimaryKey(Integer id);

    int insert(MpUser record);

    int insertSelective(MpUser record);

    MpUser selectByPrimaryKey(Integer id);

    MpUser selectByOpenId(String openId);

    MpUser selectByJiacn(String jiacn);

    int updateByPrimaryKeySelective(MpUser record);

    int updateByPrimaryKey(MpUser record);

    Page<MpUser> selectByExample(MpUser record);

    int unsubscribeByExample(MpUser record);
}