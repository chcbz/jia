package cn.jia.user.api;

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
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/role")
public class RoleController {
	
	@Autowired
	private RoleService roleService;
	@Autowired
	private UserService userService;
	
	/**
	 * 获取角色信息
	 * @param id
	 * @return
	 */
	@GetMapping(value = "/get")
	public Object findById(@RequestParam(name = "id", required = true) Integer id) {
		Role role = roleService.find(id);
		return JSONResult.success(role);
	}
	
	/**
	 * 获取当前角色下的所有用户
	 * @param page
	 * @return
	 */
	@PostMapping(value = "/get/users")
	public Object findUsers(@RequestBody JSONRequestPage<String> page) {
		Role role = JSONUtil.fromJson(page.getSearch(), Role.class);
		Page<User> userList = userService.listByRoleId(role.getId(), page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(userList.getResult());
		result.setPageNum(userList.getPageNum());
		result.setTotal(userList.getTotal());
		return result;
	}
	
	/**
	 * 获取当前角色下的所有权限
	 * @param id
	 * @return
	 */
	@PostMapping(value = "/get/perms")
	public Object findPerms(@RequestParam Integer id) {
		List<Action> permsList = roleService.listPerms(id);
		return JSONResult.success(permsList);
	}

	/**
	 * 创建角色
	 * @param role
	 * @return
	 */
	@PostMapping(value = "/create")
	public Object create(@RequestBody Role role) {
		roleService.create(role);
		return JSONResult.success();
	}

	/**
	 * 更新角色信息
	 * @param role
	 * @return
	 */
	@PostMapping(value = "/update")
	public Object update(@RequestBody Role role) {
		roleService.update(role);
		return JSONResult.success();
	}

	/**
	 * 删除角色
	 * @param id
	 * @return
	 */
	@GetMapping(value = "/delete")
	public Object delete(@RequestParam(name = "id") Integer id) {
		roleService.delete(id);
		return JSONResult.success();
	}
	
	/**
	 * 获取所有角色信息
	 * @return
	 */
	@PostMapping(value = "/list")
	public Object list(@RequestBody JSONRequestPage<String> page) {
		Page<Role> roleList = roleService.list(page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(roleList.getResult());
		result.setPageNum(roleList.getPageNum());
		result.setTotal(roleList.getTotal());
		return result;
	}
	
	/**
	 * 全量更新角色权限
	 * @param role
	 * @return
	 * @throws Exception
	 */
	@PostMapping(value = "/perms/change")
	public Object changePerms(@RequestBody Role role) throws Exception {
		roleService.changePerms(role);
		return JSONResult.success();
	}
	
	/**
	 * 批量添加角色用户或组
	 * @param role
	 * @return
	 * @throws Exception
	 */
	@PostMapping(value = "/users/add")
	public Object userBatchAdd(@RequestBody Role role) throws Exception {
		roleService.batchAddUser(role);
		return JSONResult.success();
	}
	
	/**
	 * 批量删除角色用户或组
	 * @param role
	 * @return
	 * @throws Exception
	 */
	@PostMapping(value = "/users/del")
	public Object userBatchDel(@RequestBody Role role) throws Exception {
		roleService.batchDelUser(role);
		return JSONResult.success();
	}
}
