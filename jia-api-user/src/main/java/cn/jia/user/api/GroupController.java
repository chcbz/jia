package cn.jia.user.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.pagehelper.Page;

import cn.jia.core.entity.JSONRequestPage;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.entity.JSONResultPage;
import cn.jia.core.util.JSONUtil;
import cn.jia.user.entity.Group;
import cn.jia.user.entity.User;
import cn.jia.user.service.GroupService;
import cn.jia.user.service.UserService;

@RestController
@RequestMapping("/group")
public class GroupController {
	
	@Autowired
	private GroupService groupService;
	@Autowired
	private UserService userService;
	
	/**
	 * 获取群组信息
	 * @param id
	 * @return
	 */
	@GetMapping(value = "/get")
	public Object findById(@RequestParam(name = "id", required = true) Integer id) {
		Group group = groupService.find(id);
		return JSONResult.success(group);
	}
	
	/**
	 * 获取当前群组下的所有用户
	 * @param page
	 * @return
	 */
	@PostMapping(value = "/get/users")
	public Object findUsers(@RequestBody JSONRequestPage<String> page) {
		Group group = JSONUtil.fromJson(page.getSearch(), Group.class);
		Page<User> userList = userService.listByGroupId(group.getId(), page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(userList.getResult());
		result.setPageNum(userList.getPageNum());
		result.setTotal(userList.getTotal());
		return result;
	}

	/**
	 * 创建群组
	 * @param group
	 * @return
	 */
	@PostMapping(value = "/create")
	public Object create(@RequestBody Group group) {
		groupService.create(group);
		return JSONResult.success();
	}

	/**
	 * 更新群组信息
	 * @param group
	 * @return
	 */
	@PostMapping(value = "/update")
	public Object update(@RequestBody Group group) {
		groupService.update(group);
		return JSONResult.success();
	}

	/**
	 * 删除群组
	 * @param id
	 * @return
	 */
	@GetMapping(value = "/delete")
	public Object delete(@RequestParam(name = "id") Integer id) {
		groupService.delete(id);
		return JSONResult.success();
	}
	
	/**
	 * 获取所有群组信息
	 * @return
	 */
	@PostMapping(value = "/list")
	public Object list(@RequestBody JSONRequestPage<String> page) {
		Page<Group> groupList = groupService.list(page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(groupList.getResult());
		result.setPageNum(groupList.getPageNum());
		result.setTotal(groupList.getTotal());
		return result;
	}
	
	/**
	 * 批量添加群组用户
	 * @param group
	 * @return
	 * @throws Exception
	 */
	@PostMapping(value = "/users/add")
	public Object userBatchAdd(@RequestBody Group group) throws Exception {
		groupService.batchAddUser(group);
		return JSONResult.success();
	}
	
	/**
	 * 批量删除群组用户
	 * @param group
	 * @return
	 * @throws Exception
	 */
	@PostMapping(value = "/users/del")
	public Object userBatchDel(@RequestBody Group group) throws Exception {
		groupService.batchDelUser(group);
		return JSONResult.success();
	}

    /**
     * 更新用户组角色
     * @param group
     * @return
     * @throws Exception
     */
    @PostMapping(value = "/role/change")
    public Object changeRole(@RequestBody Group group) throws Exception {
        groupService.changeRole(group);
        return JSONResult.success();
    }
}
