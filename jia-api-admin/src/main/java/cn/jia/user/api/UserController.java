package cn.jia.user.api;

import cn.jia.core.common.EsConstants;
import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.Action;
import cn.jia.core.entity.JSONRequestPage;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.entity.JSONResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.*;
import cn.jia.isp.entity.IspFile;
import cn.jia.isp.service.FileService;
import cn.jia.sms.entity.SmsCode;
import cn.jia.sms.service.SmsService;
import cn.jia.user.common.Constants;
import cn.jia.user.common.ErrorConstants;
import cn.jia.user.entity.Org;
import cn.jia.user.entity.Role;
import cn.jia.user.entity.User;
import cn.jia.user.entity.UserImport;
import cn.jia.user.service.OrgService;
import cn.jia.user.service.RoleService;
import cn.jia.user.service.UserService;
import com.github.pagehelper.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/user")
public class UserController {
	
	@Autowired
	private UserService userService;
	@Autowired
	private OrgService orgService;
	@Autowired
	private RoleService roleService;
	@Autowired
	private RedisTemplate<String, Object> redisTemplate;
	@Autowired
	private SmsService smsService;
	@Autowired
	private FileService fileService;
	@Value("${jia.file.path}")
	private String filePath;

	/**
	 * 获取用户信息
	 * @param type
	 * @param key
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('user-get')")
	@RequestMapping(value = "/get", method = RequestMethod.GET)
	public Object find(@RequestParam(name = "type", defaultValue = "id") String type, @RequestParam(name = "key") String key) throws Exception {
		User user = new User();
		if("id".equals(type)) {
			user = userService.find(Integer.valueOf(key));
		}else if("cn".equals(type)) {
			user = userService.findByJiacn(key);
		}else if("openid".equals(type)) {
			user = userService.findByOpenid(key);
		}else if("username".equals(type)) {
			user = userService.findByUsername(key);
		}else if("phone".equals(type)) {
			user = userService.findByPhone(key);
		}
		
		if(user == null) {
			throw new EsRuntimeException(ErrorConstants.USER_NOT_EXIST);
		}
		user.setRoleIds(userService.findRoleIds(user.getId(), EsSecurityHandler.clientId()));
		user.setOrgIds(userService.findOrgIds(user.getId()));
		user.setGroupIds(userService.findGroupIds(user.getId()));
		user.setPassword("******"); //隐藏密码
		return JSONResult.success(user);
	}
	
	/**
	 * 获取用户姓名
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('user-get_name')")
	@RequestMapping(value = "/get/name", method = RequestMethod.GET)
	public String findNameById(@RequestParam(name = "id") Integer id) {
		User user = userService.find(id);
		String name = "";
		if(user != null) {
			name = user.getNickname();
		}
		return name;
	}

	/**
	 * 获取用户角色列表
	 * @param page
	 * @return
	 */
	@PreAuthorize("hasAuthority('user-get_roles')")
	@RequestMapping(value = "/get/roles", method = RequestMethod.POST)
	public Object findRoles(@RequestBody JSONRequestPage<String> page) {
		User user = JSONUtil.fromJson(page.getSearch(), User.class);
		Page<Role> roleList = roleService.listByUserId(user.getId(), EsSecurityHandler.clientId(), page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(roleList.getResult());
		result.setPageNum(roleList.getPageNum());
		result.setTotal(roleList.getTotal());
		return result;
	}

	/**
	 * 获取用户组织列表
	 * @param id 用户ID
	 * @return
	 */
	@PreAuthorize("hasAuthority('user-get_orgs')")
	@RequestMapping(value = "/get/orgs", method = RequestMethod.GET)
	public Object findOrgs(@RequestParam Integer id) {
		List<Org> orgList = orgService.findByUserId(id);
		return JSONResult.success(orgList);
	}
	
	/**
	 * 检查用户是否可注册
	 * @param type
	 * @param key
	 * @return
	 */
	@PreAuthorize("hasAuthority('user-check')")
	@RequestMapping(value = "/check", method = RequestMethod.GET)
	public Object check(@RequestParam(name = "type", defaultValue = "id") String type, @RequestParam(name = "key") String key) {
		User user = new User();
		if("id".equals(type)) {
			user = userService.find(Integer.valueOf(key));
		}else if("cn".equals(type)) {
			user = userService.findByJiacn(key);
		}else if("openid".equals(type)) {
			user = userService.findByOpenid(key);
		}else if("username".equals(type)) {
			user = userService.findByUsername(key);
		}
		return JSONResult.success(user == null ? 0 : 1);
	}

	/**
	 * 创建用户
	 * @param user
	 * @return
	 */
	@PreAuthorize("hasAuthority('user-create')")
	@RequestMapping(value = "/create", method = RequestMethod.POST)
	public Object create(@RequestBody User user) throws Exception {
		userService.create(user);
		return JSONResult.success(user);
	}

	/**
	 * 更新用户信息
	 * @param user
	 * @return
	 */
	@PreAuthorize("hasAuthority('user-update')")
	@RequestMapping(value = "/update", method = RequestMethod.POST)
	public Object update(@RequestBody User user) {
		userService.update(user);
		return JSONResult.success(user);
	}
	
	/**
	 * 批量同步用户
	 * @param userList
	 * @return
	 */
	@PreAuthorize("hasAuthority('user-sync')")
	@RequestMapping(value = "/sync", method = RequestMethod.POST)
	public Object sync(@RequestBody List<User> userList) throws Exception {
		userService.sync(userList);
		return JSONResult.success();
	}

	/**
	 * 删除用户
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('user-delete')")
	@RequestMapping(value = "/delete", method = RequestMethod.GET)
	public Object delete(@RequestParam(name = "id") Integer id) {
		userService.delete(id);
		return JSONResult.success();
	}
	
	/**
	 * 获取所有用户信息
	 * @return
	 */
	@PreAuthorize("hasAuthority('user-list')")
	@RequestMapping(value = "/list", method = RequestMethod.POST)
	public Object list(@RequestBody JSONRequestPage<String> page) {
		User example = JSONUtil.fromJson(page.getSearch(), User.class);
		Page<User> userList = userService.list(example, page.getPageNum(), page.getPageSize());
		//隐藏密码
		for(User u : userList.getResult()) {
			u.setRoleIds(userService.findRoleIds(u.getId(), EsSecurityHandler.clientId()));
			u.setOrgIds(userService.findOrgIds(u.getId()));
			u.setGroupIds(userService.findGroupIds(u.getId()));
			u.setPassword("******");
		}
		JSONResultPage<Object> result = new JSONResultPage<>(userList.getResult());
		result.setPageNum(userList.getPageNum());
		result.setTotal(userList.getTotal());
		return result;
	}
	
	/**
	 * 根据用户信息查找用户
	 * @param page
	 * @return
	 */
	@PreAuthorize("hasAuthority('user-search')")
	@RequestMapping(value = "/search", method = RequestMethod.POST)
	public Object search(@RequestBody JSONRequestPage<String> page) {
		User user = JSONUtil.fromJson(page.getSearch(), User.class);
		Page<User> userList = userService.search(user, page.getPageNum(), page.getPageSize());
		//隐藏密码
		for(User u : userList.getResult()) {
			u.setPassword("******");
		}
		JSONResultPage<Object> result = new JSONResultPage<>(userList.getResult());
		result.setPageNum(userList.getPageNum());
		result.setTotal(userList.getTotal());
		return result;
	}
	
	/**
	 * 修改用户积分
	 * @param jiacn Jia账号
	 * @param num 积分变化数量，可以为负数
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('user-point_change')")
	@RequestMapping(value = "/point/change", method = RequestMethod.GET)
	public Object changePoint(@RequestParam("jiacn") String jiacn, @RequestParam("num") int num) throws Exception {
		userService.changePoint(jiacn, num);
		return JSONResult.success();
	}
	
	/**
	 * 更新用户角色
	 * @param user
	 * @return
	 */
	@PreAuthorize("hasAuthority('user-role_change')")
	@RequestMapping(value = "/role/change", method = RequestMethod.POST)
	public Object changeRole(@RequestBody User user) {
		userService.changeRole(user, EsSecurityHandler.clientId());
		return JSONResult.success();
	}

	/**
	 * 更新用户组
	 * @param user
	 * @return
	 */
	@PreAuthorize("hasAuthority('user-group_change')")
	@RequestMapping(value = "/group/change", method = RequestMethod.POST)
	public Object changeGroup(@RequestBody User user) {
		userService.changeGroup(user);
		return JSONResult.success();
	}
	
	/**
	 * 更新用户组织
	 * @param user
	 * @return
	 */
	@PreAuthorize("hasAuthority('user-org_change')")
	@RequestMapping(value = "/org/change", method = RequestMethod.POST)
	public Object changeOrg(@RequestBody User user) {
		userService.changeOrg(user);
		userService.setDefaultOrg(user.getId());
		return JSONResult.success();
	}
	
	/**
	 * 获取用户OAUTH2权限信息
	 * @param user
	 * @return
	 */
	@PreAuthorize("hasAuthority('user-info')")
	@RequestMapping(value = "/info", method = RequestMethod.GET)
    public Principal info(Principal user){
        return user;
    }
	
	/**
	 * 修改用户密码
	 * @param userId 用户ID
	 * @param oldPassword 旧密码
	 * @param newPassword 新密码
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('user-password_change')")
	@RequestMapping(value = "/password/change", method = RequestMethod.GET)
	public Object changePassword(@RequestParam Integer userId, @RequestParam String oldPassword, @RequestParam String newPassword) throws Exception {
		userService.changePassword(userId, oldPassword, newPassword);
		return JSONResult.success();
	}

	/**
	 * 重置密码
	 * @param phone 手机号码
	 * @param smsCode 验证码
	 * @param newPassword 新密码
	 * @return
	 * @throws Exception
	 */
	@RequestMapping(value = "/password/reset", method = RequestMethod.GET)
	public Object resetPassword(@RequestParam String phone, @RequestParam String smsCode, @RequestParam String newPassword) throws Exception {
		SmsCode smsCodeNoUsed = smsService.selectSmsCodeNoUsed(phone, cn.jia.sms.common.Constants.SMS_CODE_TYPE_RESETPWD, EsSecurityHandler.clientId());
		if(!smsCode.equals(smsCodeNoUsed.getSmsCode())){
			throw new EsRuntimeException(cn.jia.sms.common.ErrorConstants.SMS_CODE_INCORRECT);
		}
		smsService.useSmsCode(smsCodeNoUsed.getId());
		userService.resetPassword(phone, newPassword);
		return JSONResult.success();
	}
	
	/**
	 * 批量导入用户信息
	 * @param file
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('user-batch_import')")
	@RequestMapping(value = "/batch/import", method = RequestMethod.POST)
	public Object batchImport(MultipartFile file) throws Exception {
		List<User> userList = new ArrayList<>();
		List<UserImport> userImportList = ExcelUtil.importExcel(file, 0, 1, UserImport.class);
		for(UserImport userImport : userImportList) {
			User user = new User();
			BeanUtil.copyPropertiesIgnoreNull(userImport, user);
			userList.add(user);
		}
		userService.sync(userList);
		return JSONResult.success();
	}
	
	/**
	 * 修改用户当前职位
	 * @param position
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('user-position_change')")
	@RequestMapping(value = "/position/change", method = RequestMethod.GET)
	public Object changePosition(@RequestParam(name = "position") Integer position) throws Exception {
		User user = userService.findByUsername(EsSecurityHandler.username());
		if(user == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		// 得到当前的认证信息
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        //  生成当前的所有授权
        List<GrantedAuthority> updatedAuthorities = new ArrayList<>();
        // 更新授权信息
        Org org = orgService.find(position);
		if(org != null) {
			for (Role role : roleService.listByUserId(user.getId(), org.getClientId(), 1, Integer.MAX_VALUE)) {
				List<Action> perms = roleService.listPerms(role.getId(), 1, Integer.MAX_VALUE);
				for(Action p : perms) {
					if(Constants.PERMS_STATUS_ENABLE.equals(p.getStatus())) {
						GrantedAuthority grantedAuthority = new SimpleGrantedAuthority(p.getModule()+"-"+p.getFunc());
						updatedAuthorities.add(grantedAuthority);
					}
				}
			}
			
			// 设置登录用户所属clientId
			redisTemplate.opsForValue().set("clientId_" + user.getUsername(), org.getClientId());
		}
        // 生成新的认证信息
        Authentication newAuth = new UsernamePasswordAuthenticationToken(auth.getPrincipal(), auth.getCredentials(), updatedAuthorities);
        // 重置认证信息
        SecurityContextHolder.getContext().setAuthentication(newAuth);
        // 更新用户职位
		User upUser = new User();
		upUser.setId(user.getId());
		upUser.setPosition(position);
		userService.update(upUser);
		return JSONResult.success();
	}

	/**
	 * 修改用户头像
	 * @param id
	 * @param file
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('user-update_avatar')")
	@RequestMapping(value = "/update/avatar", method = RequestMethod.POST)
	public Object updateLogo(@RequestParam Integer id, @RequestPart MultipartFile file) throws Exception {
		String filename = DateUtil.getDateString() + "_" + file.getOriginalFilename();
		File pathFile = new File(filePath + "/avatar");
		//noinspection ResultOfMethodCallIgnored
		pathFile.mkdirs();
		File f = new File(filePath + "/avatar/" + filename);
		file.transferTo(f);

		//保存文件信息
		IspFile cf = new IspFile();
		cf.setClientId(EsSecurityHandler.clientId());
		cf.setExtension(FileUtil.getExtension(filename));
		cf.setName(file.getOriginalFilename());
		cf.setSize(file.getSize());
		cf.setType(EsConstants.FILE_TYPE_AVATAR);
		cf.setUri("avatar/" + filename);
		fileService.create(cf);

		//保存用户信息
		User user = new User();
		user.setId(id);
		user.setAvatar("avatar/" + filename);
		userService.update(user);

		return JSONResult.success(user);
	}

	/**
	 * 获取当前用户的所有权限
	 * @param page 搜索条件
	 * @return 权限列表
	 */
//	@PreAuthorize("hasAuthority('user-get_perms')")
	@RequestMapping(value = "/get/perms", method = RequestMethod.POST)
	public Object findPerms(@RequestBody JSONRequestPage<String> page) {
		User user = JSONUtil.fromJson(page.getSearch(), User.class);
		Page<Action> permsList = userService.listPerms(Objects.requireNonNull(user).getId(), page.getPageNum(), page.getPageSize(), EsSecurityHandler.clientId());
		JSONResultPage<Object> result = new JSONResultPage<>(permsList.getResult());
		result.setPageNum(permsList.getPageNum());
		result.setTotal(permsList.getTotal());
		return result;
	}
}
