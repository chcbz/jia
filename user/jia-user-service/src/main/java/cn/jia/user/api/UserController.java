package cn.jia.user.api;

import cn.jia.core.common.EsConstants;
import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.redis.RedisService;
import cn.jia.core.util.*;
import cn.jia.isp.entity.IspFileEntity;
import cn.jia.isp.service.FileService;
import cn.jia.sms.common.SmsConstants;
import cn.jia.sms.common.SmsErrorConstants;
import cn.jia.sms.entity.SmsCodeEntity;
import cn.jia.sms.service.SmsService;
import cn.jia.user.common.UserConstants;
import cn.jia.user.common.UserErrorConstants;
import cn.jia.user.entity.*;
import cn.jia.user.service.OrgService;
import cn.jia.user.service.PermsService;
import cn.jia.user.service.RoleService;
import cn.jia.user.service.UserService;
import cn.jia.user.vomapper.UserVOMapper;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import cn.jia.core.util.CollectionUtil;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 用户控制器，用于处理用户相关操作
 */
@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    private UserService userService;
    @Autowired
    private OrgService orgService;
    @Autowired
    private RoleService roleService;
    @Autowired(required = false)
    private RedisService redisService;
    @Autowired(required = false)
    private SmsService smsService;
    @Autowired(required = false)
    private PermsService permsService;
    @Autowired(required = false)
    private FileService fileService;
    @Value("${jia.file.path:}")
    private String filePath;

    /**
     * 根据不同类型获取用户信息
     *
     * @param type 类型，如id、cn、openid、username、phone
     * @param key  对应类型的键值
     * @return 用户信息
     */
    @RequestMapping(value = "/get", method = RequestMethod.GET)
    public Object find(@RequestParam(name = "type", defaultValue = "id") String type,
                       @RequestParam(name = "key") String key) {
        UserEntity user = new UserEntity();
        if ("id".equals(type)) {
            user = userService.get(Long.valueOf(key));
        } else if ("cn".equals(type)) {
            user = userService.findByJiacn(key);
        } else if ("openid".equals(type)) {
            user = userService.findByOpenid(key);
        } else if ("username".equals(type)) {
            user = userService.findByUsername(key);
        } else if ("phone".equals(type)) {
            user = userService.findByPhone(key);
        }

        if (user == null) {
            throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
        }
        UserVO userVO = UserVOMapper.INSTANCE.toVO(user);
        userVO.setRoleIds(userService.findRoleIds(user.getId()));
        userVO.setOrgIds(userService.findOrgIds(user.getId()));
        userVO.setGroupIds(userService.findGroupIds(user.getId()));
        userVO.setPassword("******"); //隐藏密码
        return JsonResult.success(userVO);
    }

    /**
     * 根据ID获取用户姓名
     *
     * @param id 用户ID
     * @return 用户姓名
     */
    @PreAuthorize("hasAuthority('user-get_name')")
    @RequestMapping(value = "/get/name", method = RequestMethod.GET)
    public String findNameById(@RequestParam(name = "id") Long id) {
        UserEntity user = userService.get(id);
        String name = "";
        if (user != null) {
            name = user.getNickname();
        }
        return name;
    }

    /**
     * 获取用户角色列表
     *
     * @param page 分页请求
     * @return 角色列表
     */
    @PreAuthorize("hasAuthority('user-get_roles')")
    @RequestMapping(value = "/get/roles", method = RequestMethod.POST)
    public Object findRoles(@RequestBody JsonRequestPage<UserEntity> page) {
        UserEntity user = Optional.ofNullable(page.getSearch()).orElse(new UserEntity());
        ValidUtil.assertNotNull(user);
        PageInfo<RoleEntity> roleList = roleService.listByUserId(user.getId(), page.getPageSize(), page.getPageNum());
        JsonResultPage<RoleEntity> result = new JsonResultPage<>(roleList.getList());
        result.setPageNum(roleList.getPageNum());
        result.setTotal(roleList.getTotal());
        return result;
    }

    /**
     * 获取用户组织列表
     *
     * @param id 用户ID
     * @return 组织列表
     */
    @PreAuthorize("hasAuthority('user-get_orgs')")
    @RequestMapping(value = "/get/orgs", method = RequestMethod.GET)
    public Object findOrgs(@RequestParam Long id) {
        List<OrgEntity> orgList = orgService.findByUserId(id);
        return JsonResult.success(orgList);
    }

    /**
     * 检查用户是否可注册
     *
     * @param type 类型，如id、cn、openid、username
     * @param key  对应类型的键值
     * @return 是否可注册，0表示可注册，1表示不可注册
     */
    @PreAuthorize("hasAuthority('user-check')")
    @RequestMapping(value = "/check", method = RequestMethod.GET)
    public Object check(@RequestParam(name = "type", defaultValue = "id") String type,
                        @RequestParam(name = "key") String key) {
        UserEntity user = new UserEntity();
        if ("id".equals(type)) {
            user = userService.get(Long.valueOf(key));
        } else if ("cn".equals(type)) {
            user = userService.findByJiacn(key);
        } else if ("openid".equals(type)) {
            user = userService.findByOpenid(key);
        } else if ("username".equals(type)) {
            user = userService.findByUsername(key);
        }
        return JsonResult.success(user == null ? 0 : 1);
    }

    /**
     * 创建用户
     *
     * @param user 用户实体
     * @return 创建结果
     * @throws Exception 创建异常
     */
    @PreAuthorize("hasAuthority('user-create')")
    @RequestMapping(value = "/create", method = RequestMethod.POST)
    public Object create(@RequestBody UserEntity user) throws Exception {
        userService.create(user);
        return JsonResult.success(user);
    }

    /**
     * 更新用户信息
     *
     * @param user 用户实体
     * @return 更新结果
     */
    @PreAuthorize("hasAuthority('user-update')")
    @RequestMapping(value = "/update", method = RequestMethod.POST)
    public Object update(@RequestBody UserEntity user) {
        userService.update(user);
        return JsonResult.success(user);
    }

    /**
     * 批量同步用户
     *
     * @param userList 用户列表
     * @return 同步结果
     * @throws Exception 同步异常
     */
    @PreAuthorize("hasAuthority('user-sync')")
    @RequestMapping(value = "/sync", method = RequestMethod.POST)
    public Object sync(@RequestBody List<UserEntity> userList) throws Exception {
        userService.sync(userList);
        return JsonResult.success();
    }

    /**
     * 删除用户
     *
     * @param id 用户ID
     * @return 删除结果
     */
    @PreAuthorize("hasAuthority('user-delete')")
    @RequestMapping(value = "/delete", method = RequestMethod.GET)
    public Object delete(@RequestParam(name = "id") Long id) {
        userService.delete(id);
        return JsonResult.success();
    }

    /**
     * 获取所有用户信息
     *
     * @param page 分页请求
     * @return 用户列表
     */
    @PreAuthorize("hasAuthority('user-list')")
    @RequestMapping(value = "/list", method = RequestMethod.POST)
    public Object list(@RequestBody JsonRequestPage<UserVO> page) {
        PageInfo<UserEntity> userList = userService.findPage(page.getSearch(), page.getPageSize(), page.getPageNum());
        List<UserVO> userVOList = new ArrayList<>();
        //隐藏密码
        for (UserEntity u : userList.getList()) {
            UserVO userVO = UserVOMapper.INSTANCE.toVO(u);
            userVO.setRoleIds(userService.findRoleIds(u.getId()));
            userVO.setOrgIds(userService.findOrgIds(u.getId()));
            userVO.setGroupIds(userService.findGroupIds(u.getId()));
            userVO.setPassword("******");
            userVOList.add(userVO);
        }
        JsonResultPage<UserVO> result = new JsonResultPage<>(userVOList);
        result.setPageNum(userList.getPageNum());
        result.setTotal(userList.getTotal());
        return result;
    }

    /**
     * 根据用户信息查找用户
     *
     * @param page 分页请求
     * @return 用户列表
     */
    @PreAuthorize("hasAuthority('user-search')")
    @RequestMapping(value = "/search", method = RequestMethod.POST)
    public Object search(@RequestBody JsonRequestPage<UserEntity> page) {
        PageInfo<UserEntity> userList = userService.search(page.getSearch(), page.getPageNum(), page.getPageSize());
        //隐藏密码
        for (UserEntity u : userList.getList()) {
            u.setPassword("******");
        }
        JsonResultPage<UserEntity> result = new JsonResultPage<>(userList.getList());
        result.setPageNum(userList.getPageNum());
        result.setTotal(userList.getTotal());
        return result;
    }

    /**
     * 修改用户积分
     *
     * @param jiacn Jia账号
     * @param num   积分变化数量，可以为负数
     * @return 修改结果
     */
    @PreAuthorize("hasAuthority('user-point_change')")
    @RequestMapping(value = "/point/change", method = RequestMethod.GET)
    public Object changePoint(@RequestParam("jiacn") String jiacn, @RequestParam("num") int num) {
        userService.changePoint(jiacn, num);
        return JsonResult.success();
    }

    /**
     * 更新用户角色
     *
     * @param user 用户视图对象
     * @return 更新结果
     */
    @PreAuthorize("hasAuthority('user-role_change')")
    @RequestMapping(value = "/role/change", method = RequestMethod.POST)
    public Object changeRole(@RequestBody UserVO user) {
        userService.changeRole(user);
        return JsonResult.success();
    }

    /**
     * 更新用户组
     *
     * @param user 用户视图对象
     * @return 更新结果
     */
    @PreAuthorize("hasAuthority('user-group_change')")
    @RequestMapping(value = "/group/change", method = RequestMethod.POST)
    public Object changeGroup(@RequestBody UserVO user) {
        userService.changeGroup(user);
        return JsonResult.success();
    }

    /**
     * 更新用户组织
     *
     * @param user 用户视图对象
     * @return 更新结果
     */
    @PreAuthorize("hasAuthority('user-org_change')")
    @RequestMapping(value = "/org/change", method = RequestMethod.POST)
    public Object changeOrg(@RequestBody UserVO user) {
        userService.changeOrg(user);
        userService.setDefaultOrg(user.getId());
        return JsonResult.success();
    }

    /**
     * 获取用户OAUTH2权限信息
     *
     * @param user 当前认证用户
     * @return 返回用户权限信息
     */
    @PreAuthorize("hasAuthority('user-info')")
    @RequestMapping(value = "/info", method = RequestMethod.GET)
    public Principal info(Principal user) {
        return user;
    }

    /**
     * 获取用户信息
     *
     * @return 用户信息对象
     * @throws EsRuntimeException 如果用户不存在，则抛出异常
     */
    @RequestMapping(value = "/userinfo", method = RequestMethod.GET)
    public Object userInfo() {
        String username = EsSecurityHandler.username();
        if (StringUtil.isEmpty(username)) {
            throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
        }
        UserEntity user;
        if (username.startsWith("wx-")) {
            user = userService.findByOpenid(username.substring(3));
        } else if (username.startsWith("mb-")) {
            user = userService.findByPhone(username.substring(3));
        } else {
            user = userService.findByUsername(username);
        }
        if (user == null) {
            throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
        }
        user.setPassword("***");
        return JsonResult.success(user);
    }

    /**
     * 修改用户密码
     *
     * @param userId      用户ID
     * @param oldPassword 旧密码
     * @param newPassword 新密码
     * @return 操作结果
     * @throws EsRuntimeException 如果修改密码失败，则抛出异常
     */
    @PreAuthorize("hasAuthority('user-password_change')")
    @RequestMapping(value = "/password/change", method = RequestMethod.GET)
    public Object changePassword(@RequestParam Long userId, @RequestParam String oldPassword,
                                 @RequestParam String newPassword) {
        userService.changePassword(userId, oldPassword, newPassword);
        return JsonResult.success();
    }

    /**
     * 重置密码
     *
     * @param phone       手机号码
     * @param smsCode     验证码
     * @param newPassword 新密码
     * @return 操作结果
     * @throws EsRuntimeException 如果重置密码失败，则抛出异常
     */
    @RequestMapping(value = "/password/reset", method = RequestMethod.GET)
    public Object resetPassword(@RequestParam String phone, @RequestParam String smsCode,
                                @RequestParam String newPassword) {
        SmsCodeEntity smsCodeNoUsed =
                smsService.selectSmsCodeNoUsed(phone, SmsConstants.SMS_CODE_TYPE_RESETPWD);
        if (!smsCode.equals(smsCodeNoUsed.getSmsCode())) {
            throw new EsRuntimeException(SmsErrorConstants.SMS_CODE_INCORRECT);
        }
        smsService.useSmsCode(smsCodeNoUsed.getId());
        userService.resetPassword(phone, newPassword);
        return JsonResult.success();
    }

    /**
     * 批量导入用户信息
     *
     * @param file 用户信息Excel文件
     * @return 操作结果
     * @throws Exception 如果导入过程中发生错误，则抛出异常
     */
    @PreAuthorize("hasAuthority('user-batch_import')")
    @RequestMapping(value = "/batch/import", method = RequestMethod.POST)
    public Object batchImport(MultipartFile file) throws Exception {
        List<UserEntity> userList = new ArrayList<>();
        List<UserImport> userImportList =
                ExcelUtil.importExcel(file, 0, 1, UserImport.class);
        for (UserImport userImport : userImportList) {
            UserEntity user = new UserEntity();
            BeanUtil.copyPropertiesIgnoreNull(userImport, user);
            userList.add(user);
        }
        userService.sync(userList);
        return JsonResult.success();
    }

    /**
     * 修改用户当前职位
     *
     * @param position 新职位ID
     * @return 操作结果
     * @throws EsRuntimeException 如果用户不存在或修改失败，则抛出异常
     */
    @PreAuthorize("hasAuthority('user-position_change')")
    @RequestMapping(value = "/position/change", method = RequestMethod.GET)
    public Object changePosition(@RequestParam(name = "position") Long position) {
        UserEntity user = userService.findByUsername(EsSecurityHandler.username());
        if (user == null) {
            throw new EsRuntimeException(UserErrorConstants.DATA_NOT_FOUND);
        }
        // 得到当前的认证信息
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        //  生成当前的所有授权
        List<GrantedAuthority> updatedAuthorities = new ArrayList<>();
        // 更新授权信息
        OrgEntity org = orgService.get(position);
        if (org != null) {
            for (RoleEntity role : roleService.listByUserId(user.getId(), Integer.MAX_VALUE, 1).getList()) {
                List<PermsRelEntity> authList = roleService.listPerms(role.getId(), Integer.MAX_VALUE, 1).getList();
                if (CollectionUtil.isNullOrEmpty(authList)) {
                    continue;
                }
                PermsVO permsVO = new PermsVO();
                permsVO.setIdList(authList.stream().map(PermsRelEntity::getPermsId).collect(Collectors.toList()));
                List<PermsEntity> perms = permsService.findList(permsVO);
                for (PermsEntity p : perms) {
                    if (UserConstants.PERMS_STATUS_ENABLE.equals(p.getStatus())) {
                        GrantedAuthority grantedAuthority = new SimpleGrantedAuthority(p.getModule() + "-" + p.getFunc());
                        updatedAuthorities.add(grantedAuthority);
                    }
                }
            }

            // 设置登录用户所属clientId
            redisService.set("clientId_" + user.getUsername(), org.getClientId());
        }
        // 生成新的认证信息
        Authentication newAuth = new UsernamePasswordAuthenticationToken(auth.getPrincipal(), auth.getCredentials(), updatedAuthorities);
        // 重置认证信息
        SecurityContextHolder.getContext().setAuthentication(newAuth);
        // 更新用户职位
        UserEntity upUser = new UserEntity();
        upUser.setId(user.getId());
        upUser.setPosition(String.valueOf(position));
        userService.update(upUser);
        return JsonResult.success();
    }

    /**
     * 修改用户头像
     *
     * @param id   用户ID
     * @param file 新头像文件
     * @return 操作结果
     * @throws Exception 如果修改过程中发生错误，则抛出异常
     */
    @PreAuthorize("hasAuthority('user-update_avatar')")
    @RequestMapping(value = "/update/avatar", method = RequestMethod.POST)
    public Object updateAvatar(@RequestParam Long id, @RequestPart MultipartFile file) throws Exception {
        String filename = DateUtil.getDateString() + "_" + file.getOriginalFilename();
        File pathFile = new File(filePath + "/avatar");
        //noinspection ResultOfMethodCallIgnored
        pathFile.mkdirs();
        File f = new File(filePath + "/avatar/" + filename);
        file.transferTo(f);

        //保存文件信息
        IspFileEntity cf = new IspFileEntity();
        cf.setClientId(EsSecurityHandler.clientId());
        cf.setExtension(FileUtil.getExtension(filename));
        cf.setName(file.getOriginalFilename());
        cf.setSize(file.getSize());
        cf.setType(EsConstants.FILE_TYPE_AVATAR);
        cf.setUri("avatar/" + filename);
        fileService.create(cf);

        //保存用户信息
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setAvatar("avatar/" + filename);
        userService.update(user);

        return JsonResult.success(user);
    }
}
