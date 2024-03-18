package cn.jia.point.service;

import cn.jia.point.entity.PointRecordEntity;
import cn.jia.point.entity.PointReferralEntity;
import cn.jia.point.entity.PointSignEntity;

public interface PointService {
    PointRecordEntity sign(PointSignEntity sign);

    PointRecordEntity referral(PointReferralEntity referral);

    PointRecordEntity luck(PointRecordEntity record);

    PointRecordEntity add(String jiacn, int point, int type);

    PointRecordEntity init(PointRecordEntity record);
}
