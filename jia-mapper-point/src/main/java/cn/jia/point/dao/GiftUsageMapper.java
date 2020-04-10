package cn.jia.point.dao;

import cn.jia.point.entity.GiftUsage;
import com.github.pagehelper.Page;

public interface GiftUsageMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(GiftUsage record);

    int insertSelective(GiftUsage record);

    GiftUsage selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(GiftUsage record);

    int updateByPrimaryKey(GiftUsage record);

    Page<GiftUsage> selectByGiftId(Integer giftId);

    Page<GiftUsage> selectByUser(String jiacn);

    Page<GiftUsage> selectByExample(GiftUsage example);
}