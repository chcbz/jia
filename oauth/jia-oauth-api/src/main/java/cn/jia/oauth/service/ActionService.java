package cn.jia.oauth.service;

import cn.jia.common.service.IBaseService;
import cn.jia.oauth.entity.OauthActionEntity;

import java.util.List;

public interface ActionService extends IBaseService<OauthActionEntity> {
	void refresh(List<OauthActionEntity> resourceList);
}
