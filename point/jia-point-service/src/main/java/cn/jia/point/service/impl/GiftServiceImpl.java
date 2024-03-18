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

	@Override
	public PointGiftEntity create(PointGiftEntity gift) {
		Long now = DateUtil.nowTime();
		gift.setCreateTime(now);
		gift.setUpdateTime(now);
		pointGiftDao.insert(gift);
		return gift;
	}

	@Override
	public PointGiftEntity find(Long id) {
		PointGiftEntity gift = pointGiftDao.selectById(id);
		if(gift == null) {
			throw new EsRuntimeException(PointErrorConstants.GIFT_NOT_EXISTS);
		}
		return gift;
	}

	@Override
	public PointGiftEntity update(PointGiftEntity gift) {
		Long now = DateUtil.nowTime();
		gift.setUpdateTime(now);
		pointGiftDao.updateById(gift);
		return gift;
	}

	@Override
	public void delete(Long id) {
		pointGiftDao.deleteById(id);
	}

	@Override
	public PageInfo<PointGiftEntity> list(int pageNo, int pageSize, PointGiftVO example) {
		example = example == null ? new PointGiftVO() : example;
		PageHelper.startPage(pageNo, pageSize);
		List<PointGiftEntity> list = pointGiftDao.selectByEntity(example);
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
		PointGiftEntity gift = pointGiftDao.selectById(record.getGiftId());
		if (gift == null) {
			throw new EsRuntimeException(PointErrorConstants.GIFT_NOT_EXISTS);
		}
		ValidUtil.assertNotNull(record.getQuantity(), "PointGiftUsageEntity.Quantity");
		if (gift.getQuantity() < record.getQuantity()) {
			throw new EsRuntimeException(PointErrorConstants.GIFT_NO_ENOUGH);
		}

		ValidUtil.assertNotNull(record.getJiacn(), "PointGiftUsageEntity.Jiacn");
		UserEntity user = userService.findByJiacn(record.getJiacn());
		if (user == null) {
			throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
		}
		// 更新礼品数量
		gift.setQuantity(gift.getQuantity() - record.getQuantity());
		pointGiftDao.updateById(gift);
		//如果非现金交易，默认扣除积分
		if(record.getPrice() == null || record.getPrice().equals(0)) {
			// 更新用户积分
			int totalPoint = record.getQuantity() * gift.getPoint();
			int point = totalPoint - totalPoint * 2;
			userService.changePoint(record.getJiacn(), point);
			//记录积分情况
			PointRecordEntity pointRecord = new PointRecordEntity();
			pointRecord.setJiacn(user.getJiacn());
			pointRecord.setType(PointConstants.POINT_TYPE_REDEEM);
			pointRecord.setChg(point);
			pointRecord.setRemain(user.getPoint() + point);
			pointRecordDao.insert(pointRecord);
			// 更新兑换记录
			record.setPoint(totalPoint);
		}
		record.setName(gift.getName());
		record.setDescription(gift.getDescription());
		record.setPicUrl(gift.getPicUrl());
		pointGiftUsageDao.insert(record);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void usageCancel(Long giftUsageId) {
		PointGiftUsageEntity giftUsage = pointGiftUsageDao.selectById(giftUsageId);
		if (giftUsage == null) {
			throw new EsRuntimeException(PointErrorConstants.DATA_NOT_FOUND);
		}
		if (!PointConstants.GIFT_USAGE_STATUS_PAYED.equals(giftUsage.getStatus())) {
			throw new EsRuntimeException(PointErrorConstants.GIFT_CANNOT_CANCEL);
		}
		// 退回积分
		if (giftUsage.getPoint() != null && giftUsage.getPoint() != 0) {
			UserEntity user = userService.findByJiacn(giftUsage.getJiacn());
			if (user == null) {
				throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
			}
			UserEntity upUser = new UserEntity();
			upUser.setId(user.getId());
			upUser.setPoint(user.getPoint() + giftUsage.getPoint());
			userService.update(upUser);
		}
		// 修改状态
		PointGiftUsageEntity upUsage = new PointGiftUsageEntity();
		upUsage.setId(giftUsage.getId());
		upUsage.setStatus(PointConstants.GIFT_USAGE_STATUS_CANCEL);
		pointGiftUsageDao.updateById(upUsage);
	}

	@Override
	public void usageDelete(Long giftUsageId) {
		PointGiftUsageEntity giftUsage = pointGiftUsageDao.selectById(giftUsageId);
		if (giftUsage == null) {
			throw new EsRuntimeException(PointErrorConstants.DATA_NOT_FOUND);
		}
		if (!PointConstants.GIFT_USAGE_STATUS_DRAFT.equals(giftUsage.getStatus()) &&
				!PointConstants.GIFT_USAGE_STATUS_CANCEL.equals(giftUsage.getStatus())) {
			throw new EsRuntimeException(PointErrorConstants.GIFT_CANNOT_DELETE);
		}
		pointGiftUsageDao.deleteById(giftUsageId);
	}

	/**
	 * 获取礼品的兑换情况
	 */
	@Override
	public PageInfo<PointGiftUsageEntity> usageListByGift(int pageNum, int pageSize, Long giftId) {
		PageHelper.startPage(pageNum, pageSize);
		List<PointGiftUsageEntity> list = pointGiftUsageDao.listByGift(giftId);
		return PageInfo.of(list);
	}

	/**
	 * 获取用户的礼品兑换情况
	 */
	@Override
	public PageInfo<PointGiftUsageEntity> usageListByUser(int pageNum, int pageSize, String jiacn) {
		PageHelper.startPage(pageNum, pageSize);
		List<PointGiftUsageEntity> list = pointGiftUsageDao.listByUser(jiacn);
		return PageInfo.of(list);
	}
}
