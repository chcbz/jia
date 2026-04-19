package cn.jia.oauth.service;

import cn.jia.common.service.IBaseService;
import cn.jia.oauth.entity.OauthClientEntity;

public interface ClientService extends IBaseService<OauthClientEntity> {

	/**
	 * 根据应用标识符查询客户端
	 *
	 * @param appcn 应用标识符
	 * @return 客户端实体
	 */
	OauthClientEntity findByAppcn(String appcn);
}
