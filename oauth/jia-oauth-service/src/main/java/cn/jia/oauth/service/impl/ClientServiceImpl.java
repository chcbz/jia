package cn.jia.oauth.service.impl;

import cn.jia.core.service.BaseServiceImpl;
import cn.jia.oauth.dao.OauthClientDao;
import cn.jia.oauth.entity.OauthClientEntity;
import cn.jia.oauth.service.ClientService;
import org.springframework.stereotype.Service;

@Service
public class ClientServiceImpl extends BaseServiceImpl<OauthClientDao, OauthClientEntity> implements ClientService {
	/**
	 * 根据应用标识符查询客户端
	 *
	 * @param appcn 应用标识符
	 * @return 客户端实体
	 */
	@Override
	public OauthClientEntity findByAppcn(String appcn) {
		return baseDao.selectByAppcn(appcn);
	}
}
