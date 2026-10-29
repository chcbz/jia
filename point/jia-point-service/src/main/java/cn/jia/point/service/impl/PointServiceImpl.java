package cn.jia.point.service.impl;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.DataUtil;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.ValidUtil;
import cn.jia.point.common.PointConstants;
import cn.jia.point.common.PointErrorConstants;
import cn.jia.point.dao.PointRecordDao;
import cn.jia.point.dao.PointReferralDao;
import cn.jia.point.dao.PointSignDao;
import cn.jia.point.entity.PointRecordEntity;
import cn.jia.point.entity.PointReferralEntity;
import cn.jia.point.entity.PointSignEntity;
import cn.jia.point.service.PointAccountService;
import cn.jia.point.service.PointService;
import cn.jia.user.common.UserErrorConstants;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Point balances and business facts commit in the same transaction. */
@Service
public class PointServiceImpl implements PointService {
    private final PointSignDao pointSignDao;
    private final PointReferralDao pointReferralDao;
    private final PointRecordDao pointRecordDao;
    private final UserService userService;
    private final PointAccountService accounts;

    public PointServiceImpl(PointSignDao pointSignDao, PointReferralDao pointReferralDao,
            PointRecordDao pointRecordDao, UserService userService, PointAccountService accounts) {
        this.pointSignDao = pointSignDao;
        this.pointReferralDao = pointReferralDao;
        this.pointRecordDao = pointRecordDao;
        this.userService = userService;
        this.accounts = accounts;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PointRecordEntity sign(PointSignEntity sign) {
        ValidUtil.assertNotNull(sign, "PointSignEntity");
        ValidUtil.assertNotNull(sign.getJiacn(), "PointSignEntity.Jiacn");
        UserEntity user = requireUser(sign.getJiacn());
        accounts.lockAccounts(List.of(sign.getJiacn()));
        // Locking read after the account lock: do not reuse an older RR snapshot.
        PointSignEntity lastSign = pointSignDao.selectLatest(accounts.tenantId(), sign.getJiacn());
        Long todayStart = DateUtil.genTime(DateUtil.todayStart());
        if (lastSign != null && lastSign.getUpdateTime() >= todayStart) {
            throw new EsRuntimeException(PointErrorConstants.SIGN_NO_THE_TIME);
        }
        int balance = accounts.changeAndGet(sign.getJiacn(), PointConstants.POINT_SCORE_SIGN);
        // Existing global profile side effect remains; gray route stays disabled
        // until profile/location ownership is decoupled as well.
        UserEntity location = new UserEntity();
        location.setId(user.getId());
        location.setLatitude(sign.getLatitude());
        location.setLongitude(sign.getLongitude());
        userService.update(location);
        PointRecordEntity record = new PointRecordEntity();
        record.setJiacn(sign.getJiacn());
        saveRecord(record, PointConstants.POINT_TYPE_SIGN, PointConstants.POINT_SCORE_SIGN, balance);
        sign.setTenantId(accounts.tenantId());
        sign.setPoint(PointConstants.POINT_SCORE_SIGN);
        requireInserted(pointSignDao.insert(sign), "point sign");
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PointRecordEntity referral(PointReferralEntity referral) {
        ValidUtil.assertNotNull(referral, "PointReferralEntity");
        ValidUtil.assertNotNull(referral.getReferrer(), "PointReferralEntity.Referrer");
        ValidUtil.assertNotNull(referral.getReferral(), "PointReferralEntity.Referral");
        requireUser(referral.getReferrer());
        UserEntity referred = requireUser(referral.getReferral());
        accounts.lockAccounts(List.of(referral.getReferrer(), referral.getReferral()));
        if (pointReferralDao.checkHasReferral(accounts.tenantId(), referral.getReferral())) {
            throw new EsRuntimeException(PointErrorConstants.REFERRAL_EXISTS);
        }
        int balance = accounts.changeAndGet(referral.getReferrer(), PointConstants.POINT_SCORE_REFERRAL);
        UserEntity profile = new UserEntity();
        profile.setId(referred.getId());
        profile.setReferrer(referral.getReferrer());
        userService.update(profile);
        referral.setTenantId(accounts.tenantId());
        requireInserted(pointReferralDao.insert(referral), "point referral");
        PointRecordEntity record = new PointRecordEntity();
        record.setJiacn(referral.getReferrer());
        saveRecord(record, PointConstants.POINT_TYPE_REFERRAL, PointConstants.POINT_SCORE_REFERRAL, balance);
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PointRecordEntity luck(PointRecordEntity record) {
        ValidUtil.assertNotNull(record, "PointRecordEntity");
        ValidUtil.assertNotNull(record.getChg(), "PointRecordEntity.Chg");
        requireUser(record.getJiacn());
        int stake = record.getChg();
        if (stake <= 0) throw new IllegalArgumentException("Point stake must be positive");
        int balance = accounts.changeAndGet(record.getJiacn(), -stake);
        saveRecord(record, PointConstants.POINT_TYPE_LUCK, -stake, balance);
        int multiplier;
        if ("0519".equals(DataUtil.getRandom(true, 4))) {
            multiplier = 100;
        } else if ("11".equals(DataUtil.getRandom(true, 2))) {
            multiplier = 10;
        } else if (Integer.parseInt(DataUtil.getRandom(true, 1)) % 4 == 0) {
            multiplier = 2;
        } else {
            multiplier = 0;
        }
        int prize = Math.multiplyExact(stake, multiplier);
        balance = accounts.changeAndGet(record.getJiacn(), prize);
        record.setId(null);
        saveRecord(record, PointConstants.POINT_TYPE_LUCK, prize, balance);
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PointRecordEntity add(String jiacn, int point, int type) {
        requireUser(jiacn);
        int balance = accounts.changeAndGet(jiacn, point);
        PointRecordEntity record = new PointRecordEntity();
        record.setJiacn(jiacn);
        saveRecord(record, type, point, balance);
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PointRecordEntity init(PointRecordEntity record) {
        ValidUtil.assertNotNull(record, "PointRecordEntity");
        requireUser(record.getJiacn());
        int balance = accounts.changeAndGet(record.getJiacn(), PointConstants.POINT_SCORE_INIT);
        saveRecord(record, PointConstants.POINT_TYPE_INIT, PointConstants.POINT_SCORE_INIT, balance);
        return record;
    }

    private UserEntity requireUser(String jiacn) {
        UserEntity user = jiacn == null ? null : userService.findOwnIdentityByExactJiacn(jiacn);
        if (user == null) throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
        return user;
    }

    private void saveRecord(PointRecordEntity record, int type, int delta, int balance) {
        record.setTenantId(accounts.tenantId());
        record.setType(type);
        record.setChg(delta);
        record.setRemain(balance);
        requireInserted(pointRecordDao.insert(record), "point record");
    }

    private static void requireInserted(int rows, String fact) {
        if (rows != 1) throw new IllegalStateException(fact + " insert failed");
    }
}
