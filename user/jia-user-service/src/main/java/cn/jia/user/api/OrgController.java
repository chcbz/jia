package cn.jia.user.api;

import cn.jia.core.common.EsConstants;
import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.*;
import cn.jia.isp.entity.IspFileEntity;
import cn.jia.isp.service.FileService;
import cn.jia.user.common.UserConstants;
import cn.jia.user.common.UserErrorConstants;
import cn.jia.user.entity.*;
import cn.jia.user.service.OrgService;
import cn.jia.user.service.RoleService;
import cn.jia.user.service.UserService;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;

@RestController
@RequestMapping("/org")
public class OrgController {
	
	@Autowired
	private OrgService orgService;
	@Autowired
	private UserService userService;
	@Autowired
	private RoleService roleService;
	@Autowired(required = false)
	private FileService fileService;
	@Value("${jia.file.path}")
	private String filePath;
	
	/**
	 * 获取组织信息
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('org-get')")
	@RequestMapping(value = "/get", method = RequestMethod.GET)
	public Object findAllById(@RequestParam(name = "id") Long id) throws Exception {
		OrgEntity org = orgService.get(id);
		if(org == null) {
			throw new EsRuntimeException(UserErrorConstants.DATA_NOT_FOUND);
		}
		OrgVO orgVO = new OrgVO();
		BeanUtil.copyPropertiesIgnoreNull(org, orgVO);
		//显示负责人名称列表
		if(StringUtils.isNotEmpty(orgVO.getDirector())) {
			StringBuilder directorNames = new StringBuilder();
			for(String u : orgVO.getDirector().split(",")) {
				UserEntity user = userService.get(Long.valueOf(u));
				if(user != null) {
					directorNames.append(",").append(user.getNickname());
				}
			}
			orgVO.setDirectorNames(directorNames.substring(1));
		}
		return JsonResult.success(orgVO);
	}
	
	/**
	 * 获取父组织信息
	 * @param id
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('org-get_parent')")
	@RequestMapping(value = "/get/parent", method = RequestMethod.GET)
	public Object findParent(@RequestParam(name = "id") Long id) throws Exception {
		OrgEntity org = orgService.findParent(id);
		if(org == null) {
			throw new EsRuntimeException(UserErrorConstants.DATA_NOT_FOUND);
		}
		return JsonResult.success(org);
	}
	
	/**
	 * 获取组织姓名
	 * @param id
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('org-get_name')")
	@RequestMapping(value = "/get/name", method = RequestMethod.GET)
	public String findNameById(@RequestParam(name = "id") Long id) throws Exception {
		OrgEntity org = orgService.get(id);
		if(org == null) {
			throw new EsRuntimeException(UserErrorConstants.DATA_NOT_FOUND);
		}
		return org.getName();
	}
	
	/**
	 * 获取当前组织下的所有用户
	 * @param page
	 * @return
	 */
	@PreAuthorize("hasAuthority('org-get_users')")
	@RequestMapping(value = "/get/users", method = RequestMethod.POST)
	public Object findUsers(@RequestBody JsonRequestPage<String> page) {
		UserVO example = JsonUtil.fromJson(page.getSearch(), UserVO.class);
		ValidUtil.assertNotNull(example, UserErrorConstants.PARAMETER_INCORRECT.getCode());
		PageInfo<UserEntity> userList = userService.listByOrgId(example.getOrgId(), page.getPageSize(),
				page.getPageNum());
		JsonResultPage<UserEntity> result = new JsonResultPage<>(userList.getList());
		result.setPageNum(userList.getPageNum());
		result.setTotal(userList.getTotal());
		return result;
	}

	/**
	 * 创建组织
	 * @param org
	 * @return
	 */
	@PreAuthorize("hasAuthority('org-create')")
	@RequestMapping(value = "/create", method = RequestMethod.POST)
	public Object create(@RequestBody OrgEntity org) {
		orgService.create(org);
		return JsonResult.success();
	}

	/**
	 * 更新组织信息
	 * @param org
	 * @return
	 */
	@PreAuthorize("hasAuthority('org-update')")
	@RequestMapping(value = "/update", method = RequestMethod.POST)
	public Object update(@RequestBody OrgEntity org) {
		orgService.update(org);
		return JsonResult.success();
	}

	/**
	 * 删除组织
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('org-delete')")
	@RequestMapping(value = "/delete", method = RequestMethod.GET)
	public Object delete(@RequestParam(name = "id") Long id) {
		orgService.delete(id);
		return JsonResult.success();
	}
	
	/**
	 * 获取所有组织信息
	 * @return
	 */
	@PreAuthorize("hasAuthority('org-list')")
	@RequestMapping(value = "/list", method = RequestMethod.POST)
	public Object list(@RequestBody JsonRequestPage<String> page) {
		PageInfo<OrgEntity> orgList = orgService.list(page.getPageNum(), page.getPageSize());
		JsonResultPage<OrgEntity> result = new JsonResultPage<>(orgList.getList());
		result.setPageNum(orgList.getPageNum());
		result.setTotal(orgList.getTotal());
		return result;
	}
	
	/**
	 * 
	 * @param page
	 * @return
	 */
	@PreAuthorize("hasAuthority('org-list_sub')")
	@RequestMapping(value = "/list/sub", method = RequestMethod.POST)
	public Object listSub(@RequestBody JsonRequestPage<String> page) {
		OrgEntity org = JsonUtil.fromJson(page.getSearch(), OrgEntity.class);
		PageInfo<OrgEntity> orgList = orgService.listSub(org.getId(), page.getPageNum(), page.getPageSize());
		JsonResultPage<OrgEntity> result = new JsonResultPage<>(orgList.getList());
		result.setPageNum(orgList.getPageNum());
		result.setTotal(orgList.getTotal());
		return result;
	}

	/**
	 * 批量添加组织用户
	 * @param org
	 * @return
	 */
	@PreAuthorize("hasAuthority('org-users_add')")
	@RequestMapping(value = "/users/add", method = RequestMethod.POST)
	public Object userBatchAdd(@RequestBody OrgVO org) {
		orgService.batchAddUser(org);
		//更新用户职位
		for(Long userId : org.getUserIds()) {
			userService.setDefaultOrg(userId);
		}
		//初始化用户权限
		RoleVO role = new RoleVO();
		role.setId(UserConstants.DEFAULT_ROLE_ID);
		role.setUserIds(org.getUserIds());
		OrgEntity o = orgService.get(org.getId());
		role.setClientId(o.getClientId());
		roleService.batchAddUser(role);
		return JsonResult.success();
	}

	/**
	 * 批量删除组织用户
	 * @param org
	 * @return
	 */
	@PreAuthorize("hasAuthority('org-users_del')")
	@RequestMapping(value = "/users/del", method = RequestMethod.POST)
	public Object userBatchDel(@RequestBody OrgVO org) {
		orgService.batchDelUser(org);
		//更新用户职位
		for(Long userId : org.getUserIds()) {
			userService.setDefaultOrg(userId);
		}
		return JsonResult.success();
	}
	
	/**
	 * 根据当前职位获取不同组织的审批人
	 * @param position
	 * @param role
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('org-get_director')")
	@RequestMapping(value = "/get/director", method = RequestMethod.GET)
	public Object findDirector(@RequestParam("position") Long position, @RequestParam("role") String role) throws Exception {
		String director = orgService.findDirector(position, role);
		if(StringUtils.isEmpty(director)) {
			throw new EsRuntimeException(UserErrorConstants.ORG_DIRECTOR_NOT_EXIST);
		}
		UserVO user = null;
		for(String s : director.split(",")) {
			UserEntity u = userService.get(Long.valueOf(s));
			if(u != null && UserConstants.USER_STATUS_WORK.equals(u.getStatus())) {
				user = new UserVO();
				BeanUtil.copyPropertiesIgnoreNull(u, user);
				break;
			}
		}
		if(user == null) {
			throw new EsRuntimeException(UserErrorConstants.ORG_DIRECTOR_NOT_EXIST);
		}
		return JsonResult.success(user);
	}

	/**
	 * 修改公司LOGO
	 * @param id
	 * @param file
	 * @param type LOGO类型 1长LOGO 2方形LOGO
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('org-update_logo')")
	@RequestMapping(value = "/update/logo", method = RequestMethod.POST)
	public Object updateLogo(@RequestParam Long id, @RequestParam Long type, @RequestPart MultipartFile file) throws Exception {
		String filename = DateUtil.getDateString() + "_" + file.getOriginalFilename();
		File pathFile = new File(filePath + "/logo");
		//noinspection ResultOfMethodCallIgnored
		pathFile.mkdirs();
		File f = new File(filePath + "/logo/" + filename);
		file.transferTo(f);

		//保存文件信息
		IspFileEntity cf = new IspFileEntity();
		cf.setClientId(EsSecurityHandler.clientId());
		cf.setExtension(FileUtil.getExtension(filename));
		cf.setName(file.getOriginalFilename());
		cf.setSize(file.getSize());
		cf.setType(EsConstants.FILE_TYPE_LOGO);
		cf.setUri("logo/" + filename);
		fileService.create(cf);

		//保存组织信息
		OrgEntity org = new OrgEntity();
		org.setId(id);
		if(type.equals(1)){
			org.setLogo("logo/" + filename);
		} else {
			org.setLogoIcon("logo/" + filename);
		}

		orgService.update(org);

		return JsonResult.success(org);
	}
}
