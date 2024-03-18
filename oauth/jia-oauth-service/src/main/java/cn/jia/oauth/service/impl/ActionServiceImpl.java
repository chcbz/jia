package cn.jia.oauth.service.impl;

import cn.jia.core.common.EsConstants;
import cn.jia.core.service.BaseServiceImpl;
import cn.jia.core.util.DateUtil;
import cn.jia.oauth.dao.OauthActionDao;
import cn.jia.oauth.entity.OauthActionEntity;
import cn.jia.oauth.service.ActionService;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Named
public class ActionServiceImpl extends BaseServiceImpl<OauthActionDao, OauthActionEntity> implements ActionService {
	@Override
	@Transactional
	public void refresh(List<OauthActionEntity> resourceList) {
		Map<String, List<OauthActionEntity>> map = new HashMap<>();
		for(OauthActionEntity a : resourceList) {
			List<OauthActionEntity> alis = map.get(a.getResourceId());
			if(alis == null) {
				map.put(a.getResourceId(), new ArrayList<>() {{
					this.add(a);
				}});
			} else {
				alis.add(a);
			}
		}
		for(Map.Entry<String, List<OauthActionEntity>> al : map.entrySet()) {
			List<OauthActionEntity> moduleResource = baseDao.selectByResourceId(al.getKey());
			Long now = DateUtil.nowTime();
			//失效模块所有权限
			for(OauthActionEntity p : moduleResource) {
				if(p.getSource().equals(1)) {
					p.setStatus(EsConstants.PERMS_STATUS_DISABLE);
					p.setUpdateTime(now);
					baseDao.updateById(p);
				}
			}
			//新增权限
			for(OauthActionEntity action: al.getValue()) {
				boolean exist = false;
				for(OauthActionEntity mp : moduleResource) {
					//如果该权限已存在，则重新生效
					if (action.getResourceId().equals(mp.getResourceId()) && action.getModule().equals(mp.getModule())
							&& action.getFunc().equals(mp.getFunc())) {
						mp.setStatus(EsConstants.PERMS_STATUS_ENABLE);
						mp.setUpdateTime(now);
						baseDao.updateById(mp);
						exist = true;
					}
				}
				//如果该权限不存在，则新增权限
				if(!exist) {
					action.setStatus(EsConstants.PERMS_STATUS_ENABLE);
					action.setCreateTime(now);
					action.setUpdateTime(now);
					baseDao.insert(action);
				}
			}
		}
	}

}
