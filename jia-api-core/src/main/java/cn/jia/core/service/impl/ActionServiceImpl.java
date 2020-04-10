package cn.jia.core.service.impl;

import cn.jia.core.common.EsConstants;
import cn.jia.core.dao.ActionMapper;
import cn.jia.core.entity.Action;
import cn.jia.core.service.ActionService;
import cn.jia.core.util.DateUtil;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ActionServiceImpl implements ActionService {
	
	@Autowired
	private ActionMapper actionMapper;

	@Override
	public Action create(Action action) {
		Long now = DateUtil.genTime(new Date());
		action.setCreateTime(now);
		action.setUpdateTime(now);
		actionMapper.insertSelective(action);
		return action;
	}

	@Override
	public Action find(Integer id) {
		return actionMapper.selectByPrimaryKey(id);
	}

	@Override
	public Action update(Action action) {
		Long now = DateUtil.genTime(new Date());
		action.setUpdateTime(now);
		actionMapper.updateByPrimaryKeySelective(action);
		return action;
	}

	@Override
	public void delete(Integer id) {
		actionMapper.deleteByPrimaryKey(id);
	}

	@Override
	public Page<Action> list(int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return actionMapper.selectAll();
	}
	
	@Override
	public Page<Action> findByExample(Action example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return actionMapper.findByExamplePage(example);
	}

	@Override
	@Transactional
	public void refresh(List<Action> resourceList) {
		Map<String, List<Action>> map = new HashMap<>();
		for(Action a : resourceList) {
			List<Action> alis = map.get(a.getResourceId());
			if(alis == null) {
				map.put(a.getResourceId(), new ArrayList<Action>(){{this.add(a);}});
			} else {
				alis.add(a);
			}
		}
		for(Map.Entry<String, List<Action>> al : map.entrySet()) {
			List<Action> moduleResource = actionMapper.selectByResourceId(al.getKey());
			Long now = DateUtil.genTime(new Date());
			//失效模块所有权限
			for(Action p : moduleResource) {
				if(p.getSource().equals(1)) {
					p.setStatus(EsConstants.PERMS_STATUS_DISABLE);
					p.setUpdateTime(now);
					actionMapper.updateByPrimaryKeySelective(p);
				}
			}
			//新增权限
			for(Action action: al.getValue()) {
				boolean exist = false;
				for(Action mp : moduleResource) {
					//如果该权限已存在，则重新生效
					if (action.getResourceId().equals(mp.getResourceId()) && action.getModule().equals(mp.getModule())
							&& action.getFunc().equals(mp.getFunc())) {
						mp.setStatus(EsConstants.PERMS_STATUS_ENABLE);
						mp.setUpdateTime(now);
						actionMapper.updateByPrimaryKeySelective(mp);
						exist = true;
					}
				}
				//如果该权限不存在，则新增权限
				if(!exist) {
					action.setStatus(EsConstants.PERMS_STATUS_ENABLE);
					action.setCreateTime(now);
					action.setUpdateTime(now);
					actionMapper.insertSelective(action);
				}
			}
		}
	}

}
