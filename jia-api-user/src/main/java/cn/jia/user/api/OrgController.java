package cn.jia.user.api;

import cn.jia.core.entity.JSONRequestPage;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.entity.JSONResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.BeanUtil;
import cn.jia.core.util.JSONUtil;
import cn.jia.core.util.StringUtils;
import cn.jia.user.common.Constants;
import cn.jia.user.common.ErrorConstants;
import cn.jia.user.entity.Org;
import cn.jia.user.entity.User;
import cn.jia.user.entity.UserExample;
import cn.jia.user.entity.UserVO;
import cn.jia.user.service.OrgService;
import cn.jia.user.service.UserService;
import com.github.pagehelper.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/org")
public class OrgController {
	
	@Autowired
	private OrgService orgService;
	@Autowired
	private UserService userService;

	/**
	 * 获取组织信息
	 * @param id
	 * @return
	 */
	@GetMapping(value = "/get")
	public Object findAllById(@RequestParam(name = "id", required = true) Integer id) throws Exception {
		Org org = orgService.find(id);
		if(org == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		//显示负责人名称列表
		if(StringUtils.isNotEmpty(org.getDirector())) {
			StringBuffer directorNames = new StringBuffer();
			for(String u : org.getDirector().split(",")) {
				User user = userService.find(Integer.valueOf(u));
				if(user != null) {
					directorNames.append(","+user.getNickname());
				}
			}
			org.setDirectorNames(directorNames.substring(1));
		}
		return JSONResult.success(org);
	}
	
	/**
	 * 获取父组织信息
	 * @param id
	 * @return
	 * @throws Exception
	 */
	@GetMapping(value = "/get/parent")
	public Object findParent(@RequestParam(name = "id", required = true) Integer id) throws Exception {
		Org org = orgService.findParent(id);
		if(org == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		return JSONResult.success(org);
	}
	
	/**
	 * 获取组织姓名
	 * @param id
	 * @return
	 * @throws Exception
	 */
	@GetMapping(value = "/get/name")
	public String findNameById(@RequestParam(name = "id", required = true) Integer id) throws Exception {
		Org org = orgService.find(id);
		if(org == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		return org.getName();
	}
	
	/**
	 * 获取当前组织下的所有用户
	 * @param page
	 * @return
	 */
	@PostMapping(value = "/get/users")
	public Object findUsers(@RequestBody JSONRequestPage<String> page) {
		UserExample example = JSONUtil.fromJson(page.getSearch(), UserExample.class);
		Page<User> userList = userService.listByOrgId(example, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(userList.getResult());
		result.setPageNum(userList.getPageNum());
		result.setTotal(userList.getTotal());
		return result;
	}

	/**
	 * 创建组织
	 * @param org
	 * @return
	 */
	@PostMapping(value = "/create")
	public Object create(@RequestBody Org org) {
		orgService.create(org);
		return JSONResult.success();
	}

	/**
	 * 更新组织信息
	 * @param org
	 * @return
	 */
	@PostMapping(value = "/update")
	public Object update(@RequestBody Org org) {
		orgService.update(org);
		return JSONResult.success();
	}

	/**
	 * 删除组织
	 * @param id
	 * @return
	 */
	@GetMapping(value = "/delete")
	public Object delete(@RequestParam(name = "id") Integer id) {
		orgService.delete(id);
		return JSONResult.success();
	}
	
	/**
	 * 获取所有组织信息
	 * @return
	 */
	@PostMapping(value = "/list")
	public Object list(@RequestBody JSONRequestPage<String> page) {
		Page<Org> orgList = orgService.list(page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(orgList.getResult());
		result.setPageNum(orgList.getPageNum());
		result.setTotal(orgList.getTotal());
		return result;
	}
	
	/**
	 * 
	 * @param page
	 * @return
	 */
	@PostMapping(value = "/list/sub")
	public Object listSub(@RequestBody JSONRequestPage<String> page) {
		Org org = JSONUtil.fromJson(page.getSearch(), Org.class);
		Page<Org> orgList = orgService.listSub(org.getId(), page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(orgList.getResult());
		result.setPageNum(orgList.getPageNum());
		result.setTotal(orgList.getTotal());
		return result;
	}

	/**
	 * 批量添加角色用户
	 * @param org
	 * @return
	 * @throws Exception
	 */
	@PostMapping(value = "/users/add")
	public Object userBatchAdd(@RequestBody Org org) throws Exception {
		orgService.batchAddUser(org);
		//更新用户职位
		for(Integer userId : org.getUserIds()) {
			userService.setDefaultOrg(userId);
		}
		return JSONResult.success();
	}

	/**
	 * 批量删除角色用户
	 * @param org
	 * @return
	 * @throws Exception
	 */
	@PostMapping(value = "/users/del")
	public Object userBatchDel(@RequestBody Org org) throws Exception {
		orgService.batchDelUser(org);
		//更新用户职位
		for(Integer userId : org.getUserIds()) {
			userService.setDefaultOrg(userId);
		}
		return JSONResult.success();
	}
	
	/**
	 * 根据当前职位获取不同角色的审批人
	 * @param position
	 * @param role
	 * @return
	 * @throws Exception
	 */
	@GetMapping(value = "/get/director")
	public Object findDirector(@RequestParam("position") Integer position, @RequestParam("role") String role) throws Exception {
		String director = orgService.findDirector(position, role);
		if(StringUtils.isEmpty(director)) {
			throw new EsRuntimeException(ErrorConstants.ORG_DIRECTOR_NOT_EXIST);
		}
		UserVO user = null;
		for(String s : director.split(",")) {
			User u = userService.find(Integer.valueOf(s));
			if(u != null && Constants.USER_STATUS_WORK.equals(u.getStatus())) {
				user = new UserVO();
				BeanUtil.copyPropertiesIgnoreNull(u, user);
				break;
			}
		}
		if(user == null) {
			throw new EsRuntimeException(ErrorConstants.ORG_DIRECTOR_NOT_EXIST);
		}
		return JSONResult.success(user);
	}
}
