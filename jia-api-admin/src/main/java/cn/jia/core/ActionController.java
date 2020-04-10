package cn.jia.core;

import cn.jia.core.entity.Action;
import cn.jia.core.entity.JSONRequestPage;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.entity.JSONResultPage;
import cn.jia.core.service.ActionService;
import cn.jia.core.util.JSONUtil;
import com.github.pagehelper.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/action")
public class ActionController {
	
	@Autowired
	private ActionService actionService;
	
	/**
	 * 获取资源信息
	 * @param id
	 * @return
	 */
	/*@PreAuthorize("hasAuthority('action-get')")*/
	@RequestMapping(value = "/get", method = RequestMethod.GET)
	public Object findById(@RequestParam(name = "id") Integer id) {
		Action action = actionService.find(id);
		return JSONResult.success(action);
	}
	
	/**
	 * 创建资源
	 * @param action
	 * @return
	 */
	@PreAuthorize("hasAuthority('action-create')")
	@RequestMapping(value = "/create", method = RequestMethod.POST)
	public Object create(@RequestBody Action action) {
		actionService.create(action);
		return JSONResult.success();
	}

	/**
	 * 更新资源信息
	 * @param action
	 * @return
	 */
	@PreAuthorize("hasAuthority('action-update')")
	@RequestMapping(value = "/update", method = RequestMethod.POST)
	public Object update(@RequestBody Action action) {
		actionService.update(action);
		return JSONResult.success();
	}
	
	/**
	 * 刷新整个模块的资源
	 * @param actionList
	 * @return
	 */
	@PreAuthorize("hasAuthority('action-refresh')")
	@RequestMapping(value = "/refresh", method = RequestMethod.POST)
	public Object refresh(@RequestBody List<Action> actionList) {
		actionService.refresh(actionList);
		return JSONResult.success();
	}

	/**
	 * 删除资源
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('action-delete')")
	@RequestMapping(value = "/delete", method = RequestMethod.GET)
	public Object delete(@RequestParam(name = "id") Integer id) {
		actionService.delete(id);
		return JSONResult.success();
	}
	
	/**
	 * 获取所有资源信息
	 * @return
	 */
	/*@PreAuthorize("hasAuthority('action-list')")*/
	@RequestMapping(value = "/list", method = RequestMethod.POST)
	public Object list(@RequestBody JSONRequestPage<String> page) {
		Action action = JSONUtil.fromJson(page.getSearch(), Action.class);
		if(action == null){
			action = new Action();
		}
		action.setResourceId("jia-api-admin");
		Page<Action> actionList = actionService.findByExample(action, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(actionList.getResult());
		result.setPageNum(actionList.getPageNum());
		result.setTotal(actionList.getTotal());
		return result;
	}
}
