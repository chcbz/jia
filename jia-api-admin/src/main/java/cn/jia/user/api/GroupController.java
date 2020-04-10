package cn.jia.user.api;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.JSONRequestPage;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.entity.JSONResultPage;
import cn.jia.core.util.JSONUtil;
import cn.jia.user.entity.Group;
import cn.jia.user.entity.Role;
import cn.jia.user.entity.User;
import cn.jia.user.service.GroupService;
import cn.jia.user.service.RoleService;
import cn.jia.user.service.UserService;
import com.github.pagehelper.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/group")
public class GroupController {
	
	@Autowired
	private GroupService groupService;
	@Autowired
	private UserService userService;
	@Autowired
	private RoleService roleService;
	
	/**
	 * 获取群组信息
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('group-get')")
	@RequestMapping(value = "/get", method = RequestMethod.GET)
	public Object findById(@RequestParam(name = "id", required = true) Integer id) {
		Group group = groupService.find(id);
		group.setRoleIds(groupService.findRoleIds(id, EsSecurityHandler.clientId()));
		return JSONResult.success(group);
	}
	
	/**
	 * 获取当前群组下的所有用户
	 * @param page
	 * @return
	 */
	@PreAuthorize("hasAuthority('group-get_users')")
	@RequestMapping(value = "/get/users", method = RequestMethod.POST)
	public Object findUsers(@RequestBody JSONRequestPage<String> page) {
		Group group = JSONUtil.fromJson(page.getSearch(), Group.class);
		Page<User> userList = userService.listByGroupId(group.getId(), page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(userList.getResult());
		result.setPageNum(userList.getPageNum());
		result.setTotal(userList.getTotal());
		return result;
	}

	/**
	 * 获取当前群组的角色权限
	 * @param page
	 * @return
	 */
	@PreAuthorize("hasAuthority('group-get_roles')")
	@RequestMapping(value = "/get/roles", method = RequestMethod.POST)
	public Object findRoles(@RequestBody JSONRequestPage<String> page) {
		Group group = JSONUtil.fromJson(page.getSearch(), Group.class);
		Page<Role> roleList = roleService.listByGroupId(group.getId(), EsSecurityHandler.clientId(), page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(roleList.getResult());
		result.setPageNum(roleList.getPageNum());
		result.setTotal(roleList.getTotal());
		return result;
	}

	/**
	 * 创建群组
	 * @param group
	 * @return
	 */
	@PreAuthorize("hasAuthority('group-create')")
	@RequestMapping(value = "/create", method = RequestMethod.POST)
	public Object create(@RequestBody Group group) {
		group.setClientId(EsSecurityHandler.clientId());
		groupService.create(group);
		return JSONResult.success();
	}

	/**
	 * 更新群组信息
	 * @param group
	 * @return
	 */
	@PreAuthorize("hasAuthority('group-update')")
	@RequestMapping(value = "/update", method = RequestMethod.POST)
	public Object update(@RequestBody Group group) {
		groupService.update(group);
		return JSONResult.success();
	}

	/**
	 * 删除群组
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('group-delete')")
	@RequestMapping(value = "/delete", method = RequestMethod.GET)
	public Object delete(@RequestParam(name = "id") Integer id) {
		groupService.delete(id);
		return JSONResult.success();
	}
	
	/**
	 * 获取所有群组信息
	 * @return
	 */
	@PreAuthorize("hasAuthority('group-list')")
	@RequestMapping(value = "/list", method = RequestMethod.POST)
	public Object list(@RequestBody JSONRequestPage<String> page) {
		Group example = JSONUtil.fromJson(page.getSearch(), Group.class);
		if(example == null){
			example = new Group();
		}
		example.setClientId(EsSecurityHandler.clientId());
		Page<Group> groupList = groupService.list(example, page.getPageNum(), page.getPageSize());
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
	@PreAuthorize("hasAuthority('group-users_add')")
	@RequestMapping(value = "/users/add", method = RequestMethod.POST)
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
	@PreAuthorize("hasAuthority('group-users_del')")
	@RequestMapping(value = "/users/del", method = RequestMethod.POST)
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
	@PreAuthorize("hasAuthority('group-role_change')")
	@RequestMapping(value = "/role/change", method = RequestMethod.POST)
    public Object changeRole(@RequestBody Group group) throws Exception {
		group.setClientId(EsSecurityHandler.clientId());
        groupService.changeRole(group);
        return JSONResult.success();
    }
}
