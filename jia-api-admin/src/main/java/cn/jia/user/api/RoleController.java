package cn.jia.user.api;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.Action;
import cn.jia.core.entity.JSONRequestPage;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.entity.JSONResultPage;
import cn.jia.core.util.JSONUtil;
import cn.jia.user.entity.Role;
import cn.jia.user.entity.User;
import cn.jia.user.service.RoleService;
import cn.jia.user.service.UserService;
import com.github.pagehelper.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;

@RestController
@RequestMapping("/role")
public class RoleController {
	
	@Autowired
	private RoleService roleService;
	@Autowired
	private UserService userService;
	
	/**
	 * 获取角色信息
	 * @param id 角色ID
	 * @return 角色信息
	 */
	@PreAuthorize("hasAuthority('role-get')")
	@RequestMapping(value = "/get", method = RequestMethod.GET)
	public Object findById(@RequestParam(name = "id") Integer id) {
		Role role = roleService.find(id);
		return JSONResult.success(role);
	}
	
	/**
	 * 获取当前角色下的所有用户
	 * @param page 搜索条件
	 * @return 用户列表
	 */
	@PreAuthorize("hasAuthority('role-get_users')")
	@RequestMapping(value = "/get/users", method = RequestMethod.POST)
	public Object findUsers(@RequestBody JSONRequestPage<String> page) {
		Role role = JSONUtil.fromJson(page.getSearch(), Role.class);
		Page<User> userList = userService.listByRoleId(Objects.requireNonNull(role).getId(), page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(userList.getResult());
		result.setPageNum(userList.getPageNum());
		result.setTotal(userList.getTotal());
		return result;
	}

	/**
	 * 获取当前角色下的所有权限
	 * @param page 搜索条件
	 * @return 权限列表
	 */
//	@PreAuthorize("hasAuthority('role-get_perms')")
	@RequestMapping(value = "/get/perms", method = RequestMethod.POST)
	public Object findPerms(@RequestBody JSONRequestPage<String> page) {
		Role role = JSONUtil.fromJson(page.getSearch(), Role.class);
		Page<Action> permsList = roleService.listPerms(Objects.requireNonNull(role).getId(), page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(permsList.getResult());
		result.setPageNum(permsList.getPageNum());
		result.setTotal(permsList.getTotal());
		return result;
	}

	/**
	 * 创建角色
	 * @param role 角色信息
	 * @return 角色信息
	 */
	@PreAuthorize("hasAuthority('role-create')")
	@RequestMapping(value = "/create", method = RequestMethod.POST)
	public Object create(@RequestBody Role role) {
		role.setClientId(EsSecurityHandler.clientId());
		roleService.create(role);
		return JSONResult.success();
	}

	/**
	 * 更新角色信息
	 * @param role 角色信息
	 * @return 角色信息
	 */
	@PreAuthorize("hasAuthority('role-update')")
	@RequestMapping(value = "/update", method = RequestMethod.POST)
	public Object update(@RequestBody Role role) {
		roleService.update(role);
		return JSONResult.success();
	}

	/**
	 * 删除角色
	 * @param id 角色ID
	 * @return 处理结果
	 */
	@PreAuthorize("hasAuthority('role-delete')")
	@RequestMapping(value = "/delete", method = RequestMethod.GET)
	public Object delete(@RequestParam(name = "id") Integer id) {
		roleService.delete(id);
		return JSONResult.success();
	}
	
	/**
	 * 获取所有角色信息
	 * @return 角色信息
	 */
	@PreAuthorize("hasAuthority('role-list')")
	@RequestMapping(value = "/list", method = RequestMethod.POST)
	public Object list(@RequestBody JSONRequestPage<String> page) {
		Role example = JSONUtil.fromJson(page.getSearch(), Role.class);
		if(example == null){
			example = new Role();
		}
		example.setClientId(EsSecurityHandler.clientId());
		Page<Role> roleList = roleService.list(example, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(roleList.getResult());
		result.setPageNum(roleList.getPageNum());
		result.setTotal(roleList.getTotal());
		return result;
	}
	
	/**
	 * 全量更新角色权限
	 * @param role 角色权限信息
	 * @return 处理结果
	 */
	@PreAuthorize("hasAuthority('role-perms_change')")
	@RequestMapping(value = "/perms/change", method = RequestMethod.POST)
	public Object changePerms(@RequestBody Role role) {
		roleService.changePerms(role);
		return JSONResult.success();
	}
	
	/**
	 * 批量添加角色用户或组
	 * @param role 角色用户或组
	 * @return 处理结果
	 */
	@PreAuthorize("hasAuthority('role-users_add')")
	@RequestMapping(value = "/users/add", method = RequestMethod.POST)
	public Object userBatchAdd(@RequestBody Role role) {
		role.setClientId(EsSecurityHandler.clientId());
		roleService.batchAddUser(role);
		return JSONResult.success();
	}
	
	/**
	 * 批量删除角色用户或组
	 * @param role 角色用户或组
	 * @return 处理结果
	 */
	@PreAuthorize("hasAuthority('role-users_del')")
	@RequestMapping(value = "/users/del", method = RequestMethod.POST)
	public Object userBatchDel(@RequestBody Role role) {
		role.setClientId(EsSecurityHandler.clientId());
		roleService.batchDelUser(role);
		return JSONResult.success();
	}
}
