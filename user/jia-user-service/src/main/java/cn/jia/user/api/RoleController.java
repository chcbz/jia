package cn.jia.user.api;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.ValidUtil;
import cn.jia.oauth.entity.OauthActionEntity;
import cn.jia.oauth.entity.OauthActionVO;
import cn.jia.oauth.service.ActionService;
import cn.jia.user.common.UserErrorConstants;
import cn.jia.user.entity.AuthEntity;
import cn.jia.user.entity.RoleEntity;
import cn.jia.user.entity.RoleVO;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.RoleService;
import cn.jia.user.service.UserService;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/role")
public class RoleController {
	
	@Autowired
	private RoleService roleService;
	@Autowired
	private UserService userService;
	@Autowired(required = false)
	private ActionService actionService;
	
	/**
	 * 获取角色信息
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('role-get')")
	@RequestMapping(value = "/get", method = RequestMethod.GET)
	public Object findById(@RequestParam(name = "id") Long id) {
		RoleEntity role = roleService.get(id);
		return JsonResult.success(role);
	}
	
	/**
	 * 获取当前角色下的所有用户
	 * @param page
	 * @return
	 */
	@PreAuthorize("hasAuthority('role-get_users')")
	@RequestMapping(value = "/get/users", method = RequestMethod.POST)
	public Object findUsers(@RequestBody JsonRequestPage<String> page) {
		RoleEntity role = JsonUtil.fromJson(page.getSearch(), RoleEntity.class);
		ValidUtil.assertNotNull(role, UserErrorConstants.PARAMETER_INCORRECT.getCode());
		PageInfo<UserEntity> userList = userService.listByRoleId(role.getId(), page.getPageSize(), page.getPageNum());
		JsonResultPage<UserEntity> result = new JsonResultPage<>(userList.getList());
		result.setPageNum(userList.getPageNum());
		result.setTotal(userList.getTotal());
		return result;
	}

	/**
	 * 获取当前角色下的所有权限
	 * @param page
	 * @return
	 */
	@PreAuthorize("hasAuthority('role-get_perms')")
	@RequestMapping(value = "/get/perms", method = RequestMethod.POST)
	public Object findPerms(@RequestBody JsonRequestPage<String> page) {
		RoleEntity role = JsonUtil.fromJson(page.getSearch(), RoleEntity.class);
		if (role == null) {
			throw new EsRuntimeException(UserErrorConstants.PARAMETER_INCORRECT);
		}
		PageInfo<AuthEntity> permsList = roleService.listPerms(role.getId(), page.getPageSize(), page.getPageNum());
		OauthActionVO actionQueryVO = new OauthActionVO();
		actionQueryVO.setIdList(permsList.getList().stream().map(AuthEntity::getPermsId).collect(Collectors.toList()));
		List<OauthActionEntity> list = actionService.findList(actionQueryVO);
		JsonResultPage<OauthActionEntity> result = new JsonResultPage<>(list);
		result.setPageNum(permsList.getPageNum());
		result.setTotal(permsList.getTotal());
		return result;
	}

	/**
	 * 创建角色
	 * @param role
	 * @return
	 */
	@PreAuthorize("hasAuthority('role-create')")
	@RequestMapping(value = "/create", method = RequestMethod.POST)
	public Object create(@RequestBody RoleEntity role) {
		roleService.create(role);
		return JsonResult.success();
	}

	/**
	 * 更新角色信息
	 * @param role
	 * @return
	 */
	@PreAuthorize("hasAuthority('role-update')")
	@RequestMapping(value = "/update", method = RequestMethod.POST)
	public Object update(@RequestBody RoleEntity role) {
		roleService.update(role);
		return JsonResult.success();
	}

	/**
	 * 删除角色
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('role-delete')")
	@RequestMapping(value = "/delete", method = RequestMethod.GET)
	public Object delete(@RequestParam(name = "id") Long id) {
		roleService.delete(id);
		return JsonResult.success();
	}
	
	/**
	 * 获取所有角色信息
	 * @return
	 */
	@PreAuthorize("hasAuthority('role-list')")
	@RequestMapping(value = "/list", method = RequestMethod.POST)
	public Object list(@RequestBody JsonRequestPage<String> page) {
		RoleEntity example = JsonUtil.fromJson(page.getSearch(), RoleEntity.class);
		PageInfo<RoleEntity> roleList = roleService.findPage(example, page.getPageSize(), page.getPageNum());
		JsonResultPage<RoleEntity> result = new JsonResultPage<>(roleList.getList());
		result.setPageNum(roleList.getPageNum());
		result.setTotal(roleList.getTotal());
		return result;
	}
	
	/**
	 * 全量更新角色权限
	 * @param role
	 * @return
	 */
	@PreAuthorize("hasAuthority('role-perms_change')")
	@RequestMapping(value = "/perms/change", method = RequestMethod.POST)
	public Object changePerms(@RequestBody RoleVO role) {
		roleService.changePerms(role);
		return JsonResult.success();
	}
	
	/**
	 * 批量添加角色用户或组
	 * @param role
	 * @return
	 */
	@PreAuthorize("hasAuthority('role-users_add')")
	@RequestMapping(value = "/users/add", method = RequestMethod.POST)
	public Object userBatchAdd(@RequestBody RoleVO role) {
		role.setClientId(EsSecurityHandler.clientId());
		roleService.batchAddUser(role);
		return JsonResult.success();
	}
	
	/**
	 * 批量删除角色用户或组
	 * @param role
	 * @return
	 */
	@PreAuthorize("hasAuthority('role-users_del')")
	@RequestMapping(value = "/users/del", method = RequestMethod.POST)
	public Object userBatchDel(@RequestBody RoleVO role) {
		role.setClientId(EsSecurityHandler.clientId());
		roleService.batchDelUser(role);
		return JsonResult.success();
	}
}
