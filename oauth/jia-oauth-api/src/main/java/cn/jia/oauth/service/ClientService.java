package cn.jia.oauth.service;

import cn.jia.common.service.IBaseService;
import cn.jia.oauth.entity.OauthClientEntity;

public interface ClientService extends IBaseService<OauthClientEntity> {

	OauthClientEntity findByAppcn(String appcn);
}
