package cn.jia.user.api;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.Action;
import cn.jia.core.entity.JSONRequestPage;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.entity.JSONResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.BeanUtil;
import cn.jia.core.util.ExcelUtil;
import cn.jia.core.util.JSONUtil;
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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

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

	/**
	 * 获取用户信息
	 * @param type
	 * @param key
	 * @return
	 * @throws Exception
	 */
	/*@PreAuthorize("hasAuthority('user-get')")*/
	@GetMapping(value = "/get")
	public Object find(@RequestParam(name = "type", defaultValue = "id") String type, @RequestParam(name = "key", required = true) String key) throws Exception {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		 if (authentication.getPrincipal() instanceof UserDetails) {
			System.out.println("");
			
		}
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
		user.setRoleIds(userService.findRoleIds(user.getId()));
		user.setOrgIds(userService.findOrgIds(user.getId()));
		user.setGroupIds(userService.findGroupIds(user.getId()));
		user.setPassword("******"); //隐藏密码
		return JSONResult.success(user);
	}
	
	/**
	 * 获取用户姓名
	 * @param id
	 * @return
	 * @throws Exception
	 */
	@GetMapping(value = "/get/name")
	public String findNameById(@RequestParam(name = "id", required = true) Integer id) throws Exception {
		User user = userService.find(id);
		String name = "";
		if(user != null) {
			name = user.getNickname();
		}
		return name;
	}
	
	/**
	 * 检查用户是否可注册
	 * @param type
	 * @param key
	 * @return
	 * @throws Exception
	 */
	@GetMapping(value = "/check")
	public Object check(@RequestParam(name = "type", defaultValue = "id") String type, @RequestParam(name = "key", required = true) String key) throws Exception {
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
	@PostMapping(value = "/create")
	public Object create(@RequestBody User user) throws Exception {
		userService.create(user);
		return JSONResult.success(user);
	}

	/**
	 * 更新用户信息
	 * @param user
	 * @return
	 */
	@PostMapping(value = "/update")
	public Object update(@RequestBody User user) {
		userService.update(user);
		return JSONResult.success(user);
	}
	
	/**
	 * 批量同步用户
	 * @param userList
	 * @return
	 */
	@PostMapping(value = "/sync")
	public Object sync(@RequestBody List<User> userList) throws Exception {
		userService.sync(userList);
		return JSONResult.success();
	}

	/**
	 * 删除用户
	 * @param id
	 * @return
	 */
	@GetMapping(value = "/delete")
	public Object delete(@RequestParam(name = "id") Integer id) {
		userService.delete(id);
		return JSONResult.success();
	}
	
	/**
	 * 获取所有用户信息
	 * @return
	 */
	@PostMapping(value = "/list")
	public Object list(@RequestBody JSONRequestPage<String> page) {
		Page<User> userList = userService.list(page.getPageNum(), page.getPageSize());
		//隐藏密码
		for(User u : userList.getResult()) {
			u.setRoleIds(userService.findRoleIds(u.getId()));
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
	@PostMapping(value = "/search")
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
	@GetMapping(value = "/point/change")
	public Object changePoint(@RequestParam("jiacn") String jiacn, @RequestParam("num") int num) throws Exception {
		userService.changePoint(jiacn, num);
		return JSONResult.success();
	}
	
	/**
	 * 更新用户角色
	 * @param user
	 * @return
	 * @throws Exception
	 */
	@PostMapping(value = "/role/change")
	public Object changeRole(@RequestBody User user) throws Exception {
		userService.changeRole(user);
		return JSONResult.success();
	}

	/**
	 * 更新用户组
	 * @param user
	 * @return
	 * @throws Exception
	 */
	@PostMapping(value = "/group/change")
	public Object changeGroup(@RequestBody User user) throws Exception {
		userService.changeGroup(user);
		return JSONResult.success();
	}
	
	/**
	 * 更新用户组织
	 * @param user
	 * @return
	 * @throws Exception
	 */
	@PostMapping(value = "/org/change")
	public Object changeOrg(@RequestBody User user) throws Exception {
		userService.changeOrg(user);
		userService.setDefaultOrg(user.getId());
		return JSONResult.success();
	}
	
	/**
	 * 获取用户OAUTH2权限信息
	 * @param user
	 * @return
	 */
	@GetMapping("/info")
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
	@GetMapping("/password/change")
	public Object changePassword(@RequestParam Integer userId, @RequestParam String oldPassword, @RequestParam String newPassword) throws Exception {
		userService.changePassword(userId, oldPassword, newPassword);
		return JSONResult.success();
	}
	
	/**
	 * 批量导入用户信息
	 * @param file
	 * @return
	 * @throws Exception
	 */
	@PostMapping(value = "/batch/import")
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
	@GetMapping(value = "/position/change")
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
			for (Role role : roleService.findByUserId(user.getId(), org.getClientId())) {
				List<Action> perms = roleService.listPerms(role.getId());
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
}
