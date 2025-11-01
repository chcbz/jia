package cn.jia.user.api;

import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.util.BeanUtil;
import cn.jia.user.entity.GroupEntity;
import cn.jia.user.entity.GroupVO;
import cn.jia.user.entity.RoleEntity;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.GroupService;
import cn.jia.user.service.RoleService;
import cn.jia.user.service.UserService;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

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
	 * 
	 * @param id 群组ID
	 * @return 群组信息
	 */
	@PreAuthorize("hasAuthority('group-get')")
	@RequestMapping(value = "/get", method = RequestMethod.GET)
	public Object findById(@RequestParam(name = "id") Long id) {
		GroupEntity group = groupService.get(id);
		GroupVO groupVO = new GroupVO();
		BeanUtil.copyPropertiesIgnoreNull(group, groupVO);
		groupVO.setRoleIds(groupService.findRoleIds(id));
		return JsonResult.success(groupVO);
	}
	
	/**
	 * 获取当前群组下的所有用户
	 * 
	 * @param page 分页查询条件
	 * @return 当前群组下的所有用户
	 */
	@PreAuthorize("hasAuthority('group-get_users')")
	@RequestMapping(value = "/get/users", method = RequestMethod.POST)
	public Object findUsers(@RequestBody JsonRequestPage<GroupEntity> page) {
		GroupEntity group = Optional.ofNullable(page.getSearch()).orElse(new GroupEntity());
		PageInfo<UserEntity> userList = userService.listByGroupId(group.getId(), page.getPageSize(), page.getPageNum());
		JsonResultPage<UserEntity> result = new JsonResultPage<>(userList.getList());
		result.setPageNum(userList.getPageNum());
		result.setTotal(userList.getTotal());
		return result;
	}

	/**
	 * 获取当前群组的角色权限
	 * 
	 * @param page 分页查询条件
	 * @return 当前群组的角色权限
	 */
	@PreAuthorize("hasAuthority('group-get_roles')")
	@RequestMapping(value = "/get/roles", method = RequestMethod.POST)
	public Object findRoles(@RequestBody JsonRequestPage<GroupEntity> page) {
		GroupEntity group = Optional.ofNullable(page.getSearch()).orElse(new GroupEntity());
		PageInfo<RoleEntity> roleList = roleService.listByGroupId(group.getId(), page.getPageSize(), page.getPageNum());
		JsonResultPage<RoleEntity> result = new JsonResultPage<>(roleList.getList());
		result.setPageNum(roleList.getPageNum());
		result.setTotal(roleList.getTotal());
		return result;
	}

	/**
	 * 创建群组
	 * 
	 * @param group 群组信息
	 * @return 结果
	 */
	@PreAuthorize("hasAuthority('group-create')")
	@RequestMapping(value = "/create", method = RequestMethod.POST)
	public Object create(@RequestBody GroupEntity group) {
		groupService.create(group);
		return JsonResult.success();
	}

	/**
	 * 更新群组信息
	 * 
	 * @param group 群组信息
	 * @return 结果
	 */
	@PreAuthorize("hasAuthority('group-update')")
	@RequestMapping(value = "/update", method = RequestMethod.POST)
	public Object update(@RequestBody GroupEntity group) {
		groupService.update(group);
		return JsonResult.success();
	}

	/**
	 * 删除群组
	 *
	 * @param id 群组ID
	 * @return 结果
	 */
	@PreAuthorize("hasAuthority('group-delete')")
	@RequestMapping(value = "/delete", method = RequestMethod.GET)
	public Object delete(@RequestParam(name = "id") Long id) {
		groupService.delete(id);
		return JsonResult.success();
	}
	
	/**
	 * 获取所有群组信息
	 *
	 * @return 群组信息
	 */
	@PreAuthorize("hasAuthority('group-list')")
	@RequestMapping(value = "/list", method = RequestMethod.POST)
	public Object list(@RequestBody JsonRequestPage<GroupEntity> page) {
		GroupEntity example = Optional.ofNullable(page.getSearch()).orElse(new GroupEntity());
		PageInfo<GroupEntity> groupList = groupService.findPage(example, page.getPageSize(), page.getPageNum(), page.getOrderBy());
		JsonResultPage<GroupEntity> result = new JsonResultPage<>(groupList.getList());
		result.setPageNum(groupList.getPageNum());
		result.setTotal(groupList.getTotal());
		return result;
	}
	
	/**
	 * 批量添加群组用户
	 *
	 * @param group 群组信息
	 * @return 结果
	 */
	@PreAuthorize("hasAuthority('group-users_add')")
	@RequestMapping(value = "/users/add", method = RequestMethod.POST)
	public Object userBatchAdd(@RequestBody GroupVO group) {
		groupService.batchAddUser(group);
		return JsonResult.success();
	}
	
	/**
	 * 批量删除群组用户
	 *
	 * @param group 群组信息
	 * @return 结果
	 */
	@PreAuthorize("hasAuthority('group-users_del')")
	@RequestMapping(value = "/users/del", method = RequestMethod.POST)
	public Object userBatchDel(@RequestBody GroupVO group) {
		groupService.batchDelUser(group);
		return JsonResult.success();
	}

    /**
     * 更新用户组角色
	 *
     * @param group 群组信息
     * @return  结果
	 */
	@PreAuthorize("hasAuthority('group-role_change')")
	@RequestMapping(value = "/role/change", method = RequestMethod.POST)
    public Object changeRole(@RequestBody GroupVO group) {
        groupService.changeRole(group);
        return JsonResult.success();
    }
}
