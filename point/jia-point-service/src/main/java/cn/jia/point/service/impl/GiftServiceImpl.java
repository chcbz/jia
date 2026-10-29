package cn.jia.point.service.impl;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.ValidUtil;
import cn.jia.point.common.PointConstants;
import cn.jia.point.common.PointErrorConstants;
import cn.jia.point.dao.PointGiftDao;
import cn.jia.point.dao.PointGiftUsageDao;
import cn.jia.point.dao.PointRecordDao;
import cn.jia.point.entity.PointGiftEntity;
import cn.jia.point.entity.PointGiftUsageEntity;
import cn.jia.point.entity.PointGiftVO;
import cn.jia.point.entity.PointRecordEntity;
import cn.jia.point.service.GiftService;
import cn.jia.point.service.PointAccountService;
import cn.jia.user.common.UserErrorConstants;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.UserService;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class GiftServiceImpl implements GiftService {
	
	@Autowired
	private PointGiftDao pointGiftDao;
	@Autowired
	private PointGiftUsageDao pointGiftUsageDao;
	@Autowired(required = false)
	private UserService userService;
	@Autowired
	private PointRecordDao pointRecordDao;
	@Autowired
	private PointAccountService accounts;

	@Override
	public PointGiftEntity create(PointGiftEntity gift) {
		Long now = DateUtil.nowTime();
		gift.setCreateTime(now);
		gift.setUpdateTime(now);
		gift.setTenantId(accounts.tenantId());
		requireOne(pointGiftDao.insert(gift), "gift insert");
		return gift;
	}

	@Override
	public PointGiftEntity find(Long id) {
		PointGiftEntity gift = pointGiftDao.selectScoped(accounts.tenantId(), id, false);
		if(gift == null) {
			throw new EsRuntimeException(PointErrorConstants.GIFT_NOT_EXISTS);
		}
		return gift;
	}

	@Override
	public PointGiftEntity update(PointGiftEntity gift) {
		Long now = DateUtil.nowTime();
		gift.setUpdateTime(now);
		requireOne(pointGiftDao.updateScoped(accounts.tenantId(), gift), "gift update");
		return gift;
	}

	@Override
	public void delete(Long id) {
		pointGiftDao.deleteScoped(accounts.tenantId(), id);
	}

	@Override
	public PageInfo<PointGiftEntity> list(int pageNum, int pageSize, PointGiftVO example, String orderBy) {
		example = example == null ? new PointGiftVO() : example;
		PageHelper.startPage(pageNum, pageSize, orderBy);
		List<PointGiftEntity> list = pointGiftDao.listScoped(accounts.tenantId(), example);
		return PageInfo.of(list);
	}

	/**
	 * 礼品兑换
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public void usage(PointGiftUsageEntity record) {
		ValidUtil.assertNotNull(record, "PointGiftUsageEntity");
		ValidUtil.assertNotNull(record.getGiftId(), "PointGiftUsageEntity.GiftId");
		PointGiftEntity gift = pointGiftDao.selectScoped(accounts.tenantId(), record.getGiftId(), true);
		if (gift == null) {
			throw new EsRuntimeException(PointErrorConstants.GIFT_NOT_EXISTS);
		}
		ValidUtil.assertNotNull(record.getQuantity(), "PointGiftUsageEntity.Quantity");
		if (record.getQuantity() <= 0) {
            throw new IllegalArgumentException("Gift quantity must be positive");
        }
        if (record.getPrice() != null && record.getPrice() < 0) {
            throw new IllegalArgumentException("Gift price cannot be negative");
        }
        if (gift.getQuantity() == null || gift.getQuantity() < record.getQuantity()) {
			throw new EsRuntimeException(PointErrorConstants.GIFT_NO_ENOUGH);
		}

		ValidUtil.assertNotNull(record.getJiacn(), "PointGiftUsageEntity.Jiacn");
		UserEntity user = userService.findOwnIdentityByExactJiacn(record.getJiacn());
		if (user == null) {
			throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
		}
		// 更新礼品数量
		gift.setQuantity(gift.getQuantity() - record.getQuantity());
		requireOne(pointGiftDao.updateScoped(accounts.tenantId(), gift), "gift update");
		//如果非现金交易，默认扣除积分
		if(record.getPrice() == null || record.getPrice().equals(0)) {
            if (gift.getPoint() == null || gift.getPoint() < 0) {
                throw new IllegalArgumentException("Invalid gift point cost");
            }
            int totalPoint = Math.multiplyExact(record.getQuantity(), gift.getPoint());
            int balance = accounts.changeAndGet(record.getJiacn(), -totalPoint);
            savePointRecord(record, -totalPoint, balance);
			// 更新兑换记录
			record.setPoint(totalPoint);
        } else {
            // A cash order must not carry a caller-supplied refundable point amount.
            record.setPoint(0);
        }
		record.setName(gift.getName());
		record.setDescription(gift.getDescription());
		record.setPicUrl(gift.getPicUrl());
		record.setTenantId(accounts.tenantId());
		requireOne(pointGiftUsageDao.insert(record), "gift usage insert");
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void usageCancel(Long giftUsageId) {
		PointGiftUsageEntity giftUsage = pointGiftUsageDao.selectScoped(accounts.tenantId(), giftUsageId, true);
		if (giftUsage == null) {
			throw new EsRuntimeException(PointErrorConstants.DATA_NOT_FOUND);
		}
		if (!PointConstants.GIFT_USAGE_STATUS_PAYED.equals(giftUsage.getStatus())) {
			throw new EsRuntimeException(PointErrorConstants.GIFT_CANNOT_CANCEL);
		}
		// 退回积分
		if (giftUsage.getPoint() != null && giftUsage.getPoint() != 0) {
			UserEntity user = userService.findOwnIdentityByExactJiacn(giftUsage.getJiacn());
			if (user == null) {
				throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
			}
            if (giftUsage.getPoint() < 0) throw new IllegalStateException("Invalid gift point refund");
            int balance = accounts.changeAndGet(giftUsage.getJiacn(), giftUsage.getPoint());
            savePointRecord(giftUsage, giftUsage.getPoint(), balance);
		}
		// 修改状态
		PointGiftUsageEntity upUsage = new PointGiftUsageEntity();
		upUsage.setId(giftUsage.getId());
		upUsage.setStatus(PointConstants.GIFT_USAGE_STATUS_CANCEL);
		requireOne(pointGiftUsageDao.updateScoped(accounts.tenantId(), upUsage), "gift cancellation");
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void usageDelete(Long giftUsageId) {
		PointGiftUsageEntity giftUsage = pointGiftUsageDao.selectScoped(accounts.tenantId(), giftUsageId, true);
		if (giftUsage == null) {
			throw new EsRuntimeException(PointErrorConstants.DATA_NOT_FOUND);
		}
		if (!PointConstants.GIFT_USAGE_STATUS_DRAFT.equals(giftUsage.getStatus()) &&
				!PointConstants.GIFT_USAGE_STATUS_CANCEL.equals(giftUsage.getStatus())) {
			throw new EsRuntimeException(PointErrorConstants.GIFT_CANNOT_DELETE);
		}
		requireOne(pointGiftUsageDao.deleteScoped(accounts.tenantId(), giftUsageId), "gift usage delete");
	}

	/**
	 * 获取礼品的兑换情况
	 */
	@Override
	public PageInfo<PointGiftUsageEntity> usageListByGift(int pageNum, int pageSize, Long giftId, String orderBy) {
		PageHelper.startPage(pageNum, pageSize, orderBy);
		List<PointGiftUsageEntity> list = pointGiftUsageDao.listByGift(accounts.tenantId(), giftId);
		return PageInfo.of(list);
	}

	/**
	 * 获取用户的礼品兑换情况
	 */
	@Override
	public PageInfo<PointGiftUsageEntity> usageListByUser(int pageNum, int pageSize, String jiacn, String orderBy) {
		PageHelper.startPage(pageNum, pageSize, orderBy);
		List<PointGiftUsageEntity> list = pointGiftUsageDao.listByUser(accounts.tenantId(), jiacn);
		return PageInfo.of(list);
	}

    private void savePointRecord(PointGiftUsageEntity usage, int delta, int balance) {
        PointRecordEntity record = new PointRecordEntity();
        record.setTenantId(accounts.tenantId());
        record.setClientId(usage.getClientId());
        record.setJiacn(usage.getJiacn());
        record.setType(PointConstants.POINT_TYPE_REDEEM);
        record.setChg(delta);
        record.setRemain(balance);
        requireOne(pointRecordDao.insert(record), "gift point record insert");
    }

    private static void requireOne(int rows, String operation) {
        if (rows != 1) throw new IllegalStateException(operation + " failed");
    }
}
