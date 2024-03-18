package cn.jia.point.service;

import cn.jia.point.entity.PointGiftEntity;
import cn.jia.point.entity.PointGiftUsageEntity;
import cn.jia.point.entity.PointGiftVO;
import com.github.pagehelper.PageInfo;

public interface GiftService {
    PointGiftEntity create(PointGiftEntity gift);

    PointGiftEntity find(Long id);

    PointGiftEntity update(PointGiftEntity gift);

    void delete(Long id);

    PageInfo<PointGiftEntity> list(int pageNo, int pageSize, PointGiftVO example);

    void usage(PointGiftUsageEntity record) throws Exception;

    void usageCancel(Long giftUsageId);

    void usageDelete(Long giftUsageId);

    PageInfo<PointGiftUsageEntity> usageListByGift(int pageNum, int pageSize, Long giftId);

    PageInfo<PointGiftUsageEntity> usageListByUser(int pageNum, int pageSize, String jiacn);
}
