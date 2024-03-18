package cn.jia.oauth.service.impl;

import cn.jia.core.service.BaseServiceImpl;
import cn.jia.core.util.StringUtils;
import cn.jia.oauth.dao.OauthClientDao;
import cn.jia.oauth.entity.OauthClientEntity;
import cn.jia.oauth.service.ClientService;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Service
public class ClientServiceImpl extends BaseServiceImpl<OauthClientDao, OauthClientEntity> implements ClientService {

	@Override
	public OauthClientEntity findByAppcn(String appcn) {
		return baseDao.selectByAppcn(appcn);
	}

	@Override
	public void addResource(String resourceId, String clientId){
		String resourceIds = baseDao.selectById(clientId).getResourceIds();
		Set<String> resourceList = new HashSet<>();
		if(StringUtils.isNotEmpty(resourceIds)) {
			resourceList.addAll(Arrays.stream(resourceIds.split(",")).toList());
		}
		OauthClientEntity upClient = new OauthClientEntity();
		upClient.setClientId(clientId);
		resourceList.add(resourceId);
		upClient.setResourceIds(String.join(",", resourceList));
		baseDao.updateById(upClient);
	}
}
