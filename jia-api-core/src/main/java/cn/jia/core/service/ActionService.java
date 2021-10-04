package cn.jia.core.service;

import cn.jia.core.common.EsConstants;
import cn.jia.core.entity.Action;
import cn.jia.core.util.DateUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ActionService {
	
	@Autowired
	private IActionService actionService;

	public Action create(Action action) {
		Long now = DateUtil.genTime(new Date());
		action.setCreateTime(now);
		action.setUpdateTime(now);
		actionService.save(action);
		return action;
	}

	public Action find(Integer id) {
		return actionService.getById(id);
	}

	public Action update(Action action) {
		Long now = DateUtil.genTime(new Date());
		action.setUpdateTime(now);
		actionService.updateById(action);
		return action;
	}

	public void delete(Integer id) {
		actionService.removeById(id);
	}

	public PageInfo<Action> list(int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		List<Action> list = actionService.list();
		return PageInfo.of(list);
	}
	
	public PageInfo<Action> findByExample(Action example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		List<Action> list = actionService.listByEntity(example);
		return PageInfo.of(list);
	}

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
			LambdaQueryWrapper<Action> queryWrapper = Wrappers.lambdaQuery(new Action()).eq(Action::getResourceId, al.getKey());
			List<Action> moduleResource = actionService.list(queryWrapper);
			Long now = DateUtil.genTime(new Date());
			//失效模块所有权限
			for(Action p : moduleResource) {
				if(p.getSource().equals(1)) {
					p.setStatus(EsConstants.PERMS_STATUS_DISABLE);
					p.setUpdateTime(now);
					actionService.updateById(p);
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
						actionService.updateById(mp);
						exist = true;
					}
				}
				//如果该权限不存在，则新增权限
				if(!exist) {
					action.setStatus(EsConstants.PERMS_STATUS_ENABLE);
					action.setCreateTime(now);
					action.setUpdateTime(now);
					actionService.save(action);
				}
			}
		}
	}

}
