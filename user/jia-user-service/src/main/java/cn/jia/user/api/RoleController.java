package cn.jia.user.api;

import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.util.ValidUtil;
import cn.jia.user.common.UserErrorConstants;
import cn.jia.user.entity.PermsEntity;
import cn.jia.user.entity.PermsRelEntity;
import cn.jia.user.entity.PermsVO;
import cn.jia.user.entity.RoleEntity;
import cn.jia.user.entity.RoleVO;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.PermsService;
import cn.jia.user.service.RoleService;
import cn.jia.user.service.UserService;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 角色控制器，用于处理与角色相关的各种操作
 */
@RestController
@RequestMapping("/role")
public class RoleController {

	@Autowired
	private RoleService roleService;
	@Autowired
	private UserService userService;
	@Autowired
	private PermsService permsService;

	/**
	 * 根据角色ID获取角色信息
	 * @param id 角色ID
	 * @return 角色信息
	 */
	@PreAuthorize("hasAuthority('role-get')")
	@RequestMapping(value = "/get", method = RequestMethod.GET)
	public Object findById(@RequestParam(name = "id") Long id) {
		RoleEntity role = roleService.get(id);
		return JsonResult.success(role);
	}

	/**
	 * 获取当前角色下的所有用户
	 * @param page 分页请求对象
	 * @return 用户列表
	 */
	@PreAuthorize("hasAuthority('role-get_users')")
	@RequestMapping(value = "/get/users", method = RequestMethod.POST)
	public Object findUsers(@RequestBody JsonRequestPage<RoleEntity> page) {
		RoleEntity role = Optional.ofNullable(page.getSearch()).orElse(new RoleEntity());
		ValidUtil.assertNotNull(role, UserErrorConstants.PARAMETER_INCORRECT.getCode());
		PageInfo<UserEntity> userList = userService.listByRoleId(role.getId(), page.getPageSize(), page.getPageNum());
		JsonResultPage<UserEntity> result = new JsonResultPage<>(userList.getList());
		result.setPageNum(userList.getPageNum());
		result.setTotal(userList.getTotal());
		return result;
	}

	/**
	 * 获取当前角色下的所有权限
	 * @param page 分页请求对象
	 * @return 权限列表
	 */
	@PreAuthorize("hasAuthority('role-get_perms')")
	@RequestMapping(value = "/get/perms", method = RequestMethod.POST)
	public Object findPerms(@RequestBody JsonRequestPage<RoleEntity> page) {
		RoleEntity role = Optional.ofNullable(page.getSearch()).orElse(new RoleEntity());
		PageInfo<PermsRelEntity> permsList = roleService.listPerms(role.getId(), page.getPageSize(), page.getPageNum());
		PermsVO permsVO = new PermsVO();
		permsVO.setIdList(permsList.getList().stream().map(PermsRelEntity::getPermsId).collect(Collectors.toList()));
		List<PermsEntity> list = permsService.findList(permsVO);
		JsonResultPage<PermsEntity> result = new JsonResultPage<>(list);
		result.setPageNum(permsList.getPageNum());
		result.setTotal(permsList.getTotal());
		return result;
	}

	/**
	 * 创建新角色
	 * @param role 角色实体
	 * @return 创建结果
	 */
	@PreAuthorize("hasAuthority('role-create')")
	@RequestMapping(value = "/create", method = RequestMethod.POST)
	public Object create(@RequestBody RoleEntity role) {
		roleService.create(role);
		return JsonResult.success();
	}

	/**
	 * 更新角色信息
	 * @param role 角色实体
	 * @return 更新结果
	 */
	@PreAuthorize("hasAuthority('role-update')")
	@RequestMapping(value = "/update", method = RequestMethod.POST)
	public Object update(@RequestBody RoleEntity role) {
		roleService.update(role);
		return JsonResult.success();
	}

	/**
	 * 删除角色
	 * @param id 角色ID
	 * @return 删除结果
	 */
	@PreAuthorize("hasAuthority('role-delete')")
	@RequestMapping(value = "/delete", method = RequestMethod.GET)
	public Object delete(@RequestParam(name = "id") Long id) {
		roleService.delete(id);
		return JsonResult.success();
	}

	/**
	 * 获取所有角色信息
	 * @param page 分页请求对象
	 * @return 角色列表
	 */
	@PreAuthorize("hasAuthority('role-list')")
	@RequestMapping(value = "/list", method = RequestMethod.POST)
	public Object list(@RequestBody JsonRequestPage<RoleEntity> page) {
		RoleEntity example = Optional.ofNullable(page.getSearch()).orElse(new RoleEntity());
		PageInfo<RoleEntity> roleList = roleService.findPage(example, page.getPageSize(), page.getPageNum(), page.getOrderBy());
		JsonResultPage<RoleEntity> result = new JsonResultPage<>(roleList.getList());
		result.setPageNum(roleList.getPageNum());
		result.setTotal(roleList.getTotal());
		return result;
	}

	/**
	 * 全量更新角色权限
	 * @param role 角色视图对象
	 * @return 更新结果
	 */
	@PreAuthorize("hasAuthority('role-perms_change')")
	@RequestMapping(value = "/perms/change", method = RequestMethod.POST)
	public Object changePerms(@RequestBody RoleVO role) {
		roleService.changePerms(role);
		return JsonResult.success();
	}

	/**
	 * 批量添加角色用户或组
	 * @param role 角色视图对象
	 * @return 添加结果
	 */
	@PreAuthorize("hasAuthority('role-users_add')")
	@RequestMapping(value = "/users/add", method = RequestMethod.POST)
	public Object userBatchAdd(@RequestBody RoleVO role) {
		role.setClientId(EsContextHolder.getContext().getClientId());
		roleService.batchAddUser(role);
		return JsonResult.success();
	}

	/**
	 * 批量删除角色用户或组
	 * @param role 角色视图对象
	 * @return 删除结果
	 */
	@PreAuthorize("hasAuthority('role-users_del')")
	@RequestMapping(value = "/users/del", method = RequestMethod.POST)
	public Object userBatchDel(@RequestBody RoleVO role) {
		role.setClientId(EsContextHolder.getContext().getClientId());
		roleService.batchDelUser(role);
		return JsonResult.success();
	}
}
