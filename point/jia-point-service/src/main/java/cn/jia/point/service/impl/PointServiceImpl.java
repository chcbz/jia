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
import cn.jia.point.service.PointService;
import cn.jia.user.common.UserErrorConstants;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author chc
 */
@Service
public class PointServiceImpl implements PointService {
	
	@Autowired
	private PointSignDao pointSignDao;
	@Autowired
	private PointReferralDao pointReferralDao;
	@Autowired
	private PointRecordDao pointRecordDao;
	@Autowired(required = false)
	private UserService userService;

	/**
	 * 用户签到
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public PointRecordEntity sign(PointSignEntity sign) {
		ValidUtil.assertNotNull(sign, "PointSignEntity");
		ValidUtil.assertNotNull(sign.getJiacn(), "PointSignEntity.Jiacn");
		// 检查用户是否存在
		UserEntity userResult = userService.findByJiacn(sign.getJiacn());
		if (userResult == null) {
			throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
		}
		int userPoint = userResult.getPoint(); //用户当前积分
		// 查询最后一次签到时间
		PointSignEntity lastSign = pointSignDao.selectLatest(sign.getJiacn());
		Long todayStart = DateUtil.genTime(DateUtil.todayStart());
		// 判断是否可以签到
		if (lastSign != null && lastSign.getUpdateTime() > todayStart) {
			throw new EsRuntimeException(PointErrorConstants.SIGN_NO_THE_TIME);
		}
		// 增加用户积分
		userService.changePoint(sign.getJiacn(), PointConstants.POINT_SCORE_SIGN);
		//更新用户最新位置
		UserEntity params = new UserEntity();
		params.setId(userResult.getId());
		params.setLatitude(sign.getLatitude());
		params.setLongitude(sign.getLongitude());
		userService.update(params);
		//记录积分情况
		PointRecordEntity record = new PointRecordEntity();
		record.setJiacn(sign.getJiacn());
		userPoint = userPoint + PointConstants.POINT_SCORE_SIGN;
		record.setType(PointConstants.POINT_TYPE_SIGN);
		record.setChg(PointConstants.POINT_SCORE_SIGN);
		record.setRemain(userPoint);
		pointRecordDao.insert(record);
		// 添加签到记录
		sign.setPoint(PointConstants.POINT_SCORE_SIGN);
		pointSignDao.insert(sign);
		return record;
	}

	/**
	 * 用户推荐
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public PointRecordEntity referral(PointReferralEntity referral) {
		ValidUtil.assertNotNull(referral, "PointReferralEntity");
		ValidUtil.assertNotNull(referral.getReferrer(), "PointReferralEntity.Referrer");
		// 检查用户是否存在
		UserEntity userResult = userService.findByJiacn(referral.getReferrer());
		if (userResult == null) {
			throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
		}
		ValidUtil.assertNotNull(referral.getReferrer(), "PointReferralEntity.Referrer");
		UserEntity referralResult = userService.findByJiacn(referral.getReferral());
		if (referralResult == null) {
			throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
		}
		
		int userPoint = userResult.getPoint(); //用户当前积分
		//查找是否已经被推荐过
		if(pointReferralDao.checkHasReferral(referral.getReferral())) {
			throw new EsRuntimeException(PointErrorConstants.REFERRAL_EXISTS);
		}
		// 增加用户积分
		userService.changePoint(referral.getReferrer(), PointConstants.POINT_SCORE_REFERRAL);
		//更新用户推荐人信息
		UserEntity params = new UserEntity();
		params.setId(referralResult.getId());
		params.setReferrer(referral.getReferrer());
		userService.update(params);

		// 增加推荐记录
		pointReferralDao.insert(referral);
		//记录积分情况
		PointRecordEntity record = new PointRecordEntity();
		record.setJiacn(referral.getReferrer());
		userPoint = userPoint + PointConstants.POINT_SCORE_REFERRAL;
		record.setType(PointConstants.POINT_TYPE_REFERRAL);
		record.setChg(PointConstants.POINT_SCORE_REFERRAL);
		record.setRemain(userPoint);
		pointRecordDao.insert(record);
		return record;
	}

	/**
	 * 积分概率变化
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public PointRecordEntity luck(PointRecordEntity record) {
		ValidUtil.assertNotNull(record, "PointRecordEntity");
		ValidUtil.assertNotNull(record.getJiacn(), "PointRecordEntity.Jiacn");
		// 检查用户是否存在
		UserEntity userResult = userService.findByJiacn(record.getJiacn());
		if (userResult == null) {
			throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
		}
		int userPoint = userResult.getPoint(); //用户当前积分
		ValidUtil.assertNotNull(record.getChg(), "PointRecordEntity.Chg");
		int recordPoint = record.getChg(); //所使用积分
		// 扣除用户积分
		int pointChange = recordPoint - recordPoint * 2;
		userService.changePoint(record.getJiacn(), pointChange);
		//记录积分情况
		userPoint = userPoint + pointChange;
		record.setType(PointConstants.POINT_TYPE_LUCK);
		record.setChg(pointChange);
		record.setRemain(userPoint);
		pointRecordDao.insert(record);
		//积分变化规则
		if ("0519".equals(DataUtil.getRandom(true, 4))) {
			record.setChg(recordPoint * 100);
		} else if ("11".equals(DataUtil.getRandom(true, 2))) {
			record.setChg(recordPoint * 10);
		} else if (Integer.parseInt(DataUtil.getRandom(true, 1)) % 4 == 0) {
			record.setChg(recordPoint * 2);
		} else {
			record.setChg(0);
		}
		// 增加用户积分
		userService.changePoint(record.getJiacn(), record.getChg());
		//记录积分情况
		userPoint = userPoint + record.getChg();
		record.setType(PointConstants.POINT_TYPE_LUCK);
		record.setChg(record.getChg());
		record.setRemain(userPoint);
		pointRecordDao.insert(record);
		return record;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public PointRecordEntity add(String jiacn, int point, int type) {
		// 检查用户是否存在
		UserEntity userResult = userService.findByJiacn(jiacn);
		if (userResult == null) {
			throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
		}
		int userPoint = userResult.getPoint(); //用户当前积分
		// 增加用户积分
		userService.changePoint(jiacn, point);
		//记录积分情况
		userPoint = userPoint + point;
		PointRecordEntity record = new PointRecordEntity();
		record.setJiacn(jiacn);
		record.setType(type);
		record.setChg(point);
		record.setRemain(userPoint);
		pointRecordDao.insert(record);
		return record;
	}

	/**
	 * 新用户初始化积分
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public PointRecordEntity init(PointRecordEntity record) {
		ValidUtil.assertNotNull(record, "PointRecordEntity");
		// 检查用户是否存在
		UserEntity userResult = userService.findByJiacn(record.getJiacn());
		if (userResult == null) {
			throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
		}
		int userPoint = userResult.getPoint(); // 用户当前积分

		// 增加用户积分
		userService.changePoint(record.getJiacn(), PointConstants.POINT_SCORE_INIT);
		// 记录积分情况
		userPoint = userPoint + PointConstants.POINT_SCORE_INIT;
		record.setType(PointConstants.POINT_TYPE_LUCK);
		record.setChg(PointConstants.POINT_SCORE_INIT);
		record.setRemain(userPoint);
		pointRecordDao.insert(record);
		return record;
	}
}
