package cn.jia.isp.api;

import cn.jia.admin.config.security.SecurityConfiguration;
import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.JSONRequestPage;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.entity.JSONResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.ldap.LdapPage;
import cn.jia.core.ldap.LdapRequestPage;
import cn.jia.core.ldap.LdapResultPage;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.JSONUtil;
import cn.jia.isp.common.Constants;
import cn.jia.isp.common.ErrorConstants;
import cn.jia.isp.entity.*;
import cn.jia.isp.service.IspService;
import cn.jia.isp.service.LdapAccountService;
import com.github.pagehelper.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ISP服务接口
 * @author chc 2017年12月8日 下午3:31:20
 */
@RestController
@RequestMapping("/isp")
public class IspController {
	
	@Autowired
	private IspService ispService;
	@Autowired
	private LdapAccountService ldapAccountService;

	/**
	 * 服务器列表
	 * @param page 分页查询条件
	 * @param request http请求
	 * @return 服务器列表
	 */
	@PreAuthorize("hasAuthority('isp-server_list')")
	@RequestMapping(value = "/server/list", method = RequestMethod.POST)
	public Object listServer(@RequestBody JSONRequestPage<IspServer> page, HttpServletRequest request) {
		String clientId = EsSecurityHandler.checkClientId(request);
		IspServer example = page.getSearch();
		if(example == null) {
			example = new IspServer();
		}
		example.setClientId(clientId);
		Page<IspServer> ispList = ispService.listServer(example, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(ispList.getResult());
		result.setPageNum(ispList.getPageNum());
		result.setTotal(ispList.getTotal());
		return result;
	}
	
	/**
	 * 获取服务器信息
	 * @param id 服务器ID
	 * @return 服务器信息
	 */
	@PreAuthorize("hasAuthority('isp-server_get')")
	@RequestMapping(value = "/server/get", method = RequestMethod.GET)
	public Object findServerById(@RequestParam(name = "id") Integer id) throws Exception {
		IspServer record = ispService.findServer(id);
		if(record == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		return JSONResult.success(record);
	}

	/**
	 * 创建服务器
	 * @param record 服务器信息
	 * @return 服务器信息
	 */
	@PreAuthorize("hasAuthority('isp-server_create')")
	@RequestMapping(value = "/server/create", method = RequestMethod.POST)
	public Object createServer(@RequestBody IspServer record) {
		record.setClientId(EsSecurityHandler.clientId());
		Integer userId = Objects.requireNonNull(SecurityConfiguration.tokenUser()).getId();
		ispService.createServer(record, userId);
		return JSONResult.success();
	}

	/**
	 * 更新服务器信息
	 * @param record 服务器信息
	 * @return 服务器信息
	 */
	@PreAuthorize("hasAuthority('isp-server_update')")
	@RequestMapping(value = "/server/update", method = RequestMethod.POST)
	public Object updateServer(@RequestBody IspServer record) throws Exception {
		ispService.updateServer(record);
		return JSONResult.success(record);
	}

	/**
	 * 删除服务器
	 * @param id 服务器ID
	 * @return 请求结果
	 */
	@PreAuthorize("hasAuthority('isp-server_delete')")
	@RequestMapping(value = "/server/delete", method = RequestMethod.GET)
	public Object deleteServer(@RequestParam(name = "id") Integer id) {
		ispService.deleteServer(id);
		return JSONResult.success();
	}

    /**
     * 服务列表
     * @param serverId 服务器ID
     * @return 服务列表
     * @throws Exception 异常信息
     */
	@PreAuthorize("hasAuthority('isp-app_list')")
    @RequestMapping(value = "/app/list", method = RequestMethod.GET)
    public Object appList(@RequestParam Integer serverId) throws Exception {
        String result = ispService.execCommand(serverId, "curl -s -S -L https://install.wydiy.com/shell/isp.service | bash -s list");
        return JSONResult.success(JSONUtil.fromJson(result, Object.class));
    }

    /**
     * 安装服务
     * @param serverId 服务器ID
     * @return 请求结果
     */
	@PreAuthorize("hasAuthority('isp-app_install')")
    @RequestMapping(value = "/app/install", method = RequestMethod.GET)
    public Object appInstall(@RequestParam Integer serverId, @RequestParam String name) {
        Integer userId = Objects.requireNonNull(SecurityConfiguration.tokenUser()).getId();
        ispService.execCommandAsync(serverId, "curl -s -S -L https://install.wydiy.com/shell/isp.service | bash -s install " + name, userId);
        return JSONResult.success();
    }

	/**
	 * 安装服务
	 * @param info 服务信息
	 * @return 请求结果
	 * @throws Exception 异常信息
	 */
	@PreAuthorize("hasAuthority('isp-app_install')")
	@RequestMapping(value = "/app/install", method = RequestMethod.POST)
	public Object appInstall(@RequestBody Map<String, Object> info) throws Exception {
		Integer serverId = (Integer)info.get("serverId");
		IspServer server = ispService.findServer(serverId);
		if(server == null){
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		Long now = DateUtil.genTime(new Date());
		String name = String.valueOf(info.get("name"));
		String param = "";
		@SuppressWarnings({ "unchecked", "rawtypes" })
		Map<String, Object> params = (Map<String, Object>)info.get("params");
		if("openldap".equals(name)){
			String base = String.valueOf(params.get("base"));
			String user = String.valueOf(params.get("user"));
			String userDN = "cn=" + user + "," + base;
			String password = String.valueOf(params.get("password"));
			param += base + " " + userDN + " " + password;
			Integer port = (Integer)params.get("port");
			if(port != null){
				param += " " + port;
			}

			IspServer upServer = new IspServer();
			upServer.setId(serverId);
			upServer.setLdapService(Constants.COMMON_YES);
			upServer.setLdapBase(base);
			upServer.setLdapUser(user);
			upServer.setLdapPassword(password);
			upServer.setLdapPort(port);
			upServer.setUpdateTime(now);
			ispService.updateServer(upServer);
		}else if("samba".equals(name)){
			String base = String.valueOf(params.get("base"));
			String user = String.valueOf(params.get("user"));
			String userDN = "cn=" + user + "," + base;
			String password = String.valueOf(params.get("password"));
			String url = String.valueOf(params.get("url"));
			param += base + " " + userDN + " " + password + " " + url;

			IspServer upServer = new IspServer();
			upServer.setId(serverId);
			upServer.setSmbService(Constants.COMMON_YES);
			upServer.setSmbLdapBase(base);
			upServer.setSmbLdapUser(user);
			upServer.setSmbLdapPassword(password);
			upServer.setSmbLdapUrl(url);
			upServer.setUpdateTime(now);
			ispService.updateServer(upServer);
		}
		Integer userId = Objects.requireNonNull(SecurityConfiguration.tokenUser()).getId();
		ispService.execCommandAsync(serverId, "curl -s -S -L https://install.wydiy.com/shell/isp.service | bash -s install " + name + " \"" + param + "\"", userId);
		return JSONResult.success();
	}

    /**
     * 服务状态
     * @param serverId 服务器ID
     * @param name 服务名称
     * @return 服务状态
     * @throws Exception 异常信息
     */
	@PreAuthorize("hasAuthority('isp-app_status')")
    @RequestMapping(value = "/app/status", method = RequestMethod.GET)
    public Object appStatus(@RequestParam Integer serverId, @RequestParam String name) throws Exception {
        String result = ispService.execCommand(serverId, "curl -s -S -L https://install.wydiy.com/shell/isp.service | bash -s status " + name);
        return JSONResult.success(JSONUtil.fromJson(result, Object.class));
    }

    /**
     * 启动服务
     * @param serverId 服务器ID
     * @param name 服务名称
     * @return 服务状态
     * @throws Exception 异常信息
     */
	@PreAuthorize("hasAuthority('isp-app_start')")
    @RequestMapping(value = "/app/start", method = RequestMethod.GET)
    public Object appStart(@RequestParam Integer serverId, @RequestParam String name) throws Exception {
        String result = ispService.execCommand(serverId, "curl -s -S -L https://install.wydiy.com/shell/isp.service | bash -s start " + name);
        return JSONResult.success(JSONUtil.fromJson(result, Object.class));
    }

    /**
     * 停止服务
     * @param serverId 服务器ID
     * @param name 服务名称
     * @return 服务状态
     * @throws Exception 异常信息
     */
	@PreAuthorize("hasAuthority('isp-app_stop')")
    @RequestMapping(value = "/app/stop", method = RequestMethod.GET)
    public Object appStop(@RequestParam Integer serverId, @RequestParam String name) throws Exception {
        String result = ispService.execCommand(serverId, "curl -s -S -L https://install.wydiy.com/shell/isp.service | bash -s stop " + name);
        return JSONResult.success(JSONUtil.fromJson(result, Object.class));
    }

    /**
     * 重启服务
     * @param serverId 服务器ID
     * @param name 服务名称
     * @return 服务状态
     * @throws Exception 异常信息
     */
	@PreAuthorize("hasAuthority('isp-app_restart')")
    @RequestMapping(value = "/app/restart", method = RequestMethod.GET)
    public Object appRestart(@RequestParam Integer serverId, @RequestParam String name) throws Exception {
        String result = ispService.execCommand(serverId, "curl -s -S -L https://install.wydiy.com/shell/isp.service | bash -s restart " + name);
        return JSONResult.success(JSONUtil.fromJson(result, Object.class));
    }

	/**
	 * 域名列表
	 * @param page 分页查询条件
	 * @param request http请求
	 * @return 域名列表
	 */
	@PreAuthorize("hasAuthority('isp-domain_list')")
	@RequestMapping(value = "/domain/list", method = RequestMethod.POST)
	public Object listDomain(@RequestBody JSONRequestPage<IspDomain> page, HttpServletRequest request) {
		String clientId = EsSecurityHandler.checkClientId(request);
		IspDomain example = page.getSearch();
		if(example == null) {
			example = new IspDomain();
		}
		example.setClientId(clientId);
		Page<IspDomain> ispList = ispService.listDomain(example, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(ispList.getResult());
		result.setPageNum(ispList.getPageNum());
		result.setTotal(ispList.getTotal());
		return result;
	}

	/**
	 * 获取域名信息
	 * @param id 域名ID
	 * @return 域名信息
	 */
	@PreAuthorize("hasAuthority('isp-domain_get')")
	@RequestMapping(value = "/domain/get", method = RequestMethod.GET)
	public Object findDomainById(@RequestParam(name = "id") Integer id) throws Exception {
		IspDomain record = ispService.findDomain(id);
		if(record == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		return JSONResult.success(record);
	}

	/**
	 * 创建域名
	 * @param record 域名信息
	 * @return 域名信息
	 */
	@PreAuthorize("hasAuthority('isp-domain_create')")
	@RequestMapping(value = "/domain/create", method = RequestMethod.POST)
	public Object createDomain(@RequestBody IspDomain record) throws Exception {
		record.setClientId(EsSecurityHandler.clientId());
		ispService.createDomain(record);
		return JSONResult.success();
	}

	/**
	 * 更新域名信息
	 * @param record 域名信息
	 * @return 域名信息
	 */
	@PreAuthorize("hasAuthority('isp-domain_update')")
	@RequestMapping(value = "/domain/update", method = RequestMethod.POST)
	public Object updateDomain(@RequestBody IspDomain record) throws Exception {
		ispService.updateDomain(record);
		return JSONResult.success(record);
	}

	/**
	 * 删除域名
	 * @param id 域名ID
	 * @return 请求结果
	 */
	@PreAuthorize("hasAuthority('isp-domain_delete')")
	@RequestMapping(value = "/domain/delete", method = RequestMethod.GET)
	public Object deleteDomain(@RequestParam(name = "id") Integer id) {
		ispService.deleteDomain(id);
		return JSONResult.success();
	}

	/**
	 * 安装HTTPS证书
	 * @param id 域名ID
	 * @return 处理结果
	 */
	@PreAuthorize("hasAuthority('isp-domain_ssl_create')")
	@RequestMapping(value = "/domain/ssl/create", method = RequestMethod.GET)
	public Object createSSL(@RequestParam Integer id){
		Integer userId = Objects.requireNonNull(SecurityConfiguration.tokenUser()).getId();
		ispService.createSSL(id, userId);
		return JSONResult.success();
	}

	/**
	 * 安装SQL服务
	 * @param record SQL信息
	 * @return 处理结果
	 */
	@PreAuthorize("hasAuthority('isp-domain_sql_create')")
	@RequestMapping(value = "/domain/sql/create", method = RequestMethod.POST)
	public Object createSQL(@RequestBody IspDomain record) {
        Integer userId = Objects.requireNonNull(SecurityConfiguration.tokenUser()).getId();
		ispService.createSQL(record, userId);
		return JSONResult.success();
	}

	/**
	 * 安装CMS服务
	 * @param id 域名ID
	 * @return 处理结果
	 */
	@PreAuthorize("hasAuthority('isp-domain_cms_create')")
	@RequestMapping(value = "/domain/cms/create", method = RequestMethod.GET)
	public Object createCMS(@RequestParam Integer id) throws Exception{
		Integer userId = Objects.requireNonNull(SecurityConfiguration.tokenUser()).getId();
		ispService.createCMS(id, userId);
		return JSONResult.success();
	}

	/**
	 * 根据uid获取账户信息
	 * @param uid 账户uid
	 * @return 账户信息
	 */
	@PreAuthorize("hasAuthority('isp-ldap_account_get')")
	@RequestMapping(value = "/ldap/account/get", method = RequestMethod.GET)
	public Object findLdapAccountByUid(@RequestParam Integer serverId, @RequestParam String uid) {
		LdapAccount user = ldapAccountService.findByUid(serverId, uid);
		return JSONResult.success(user);
	}

	/**
	 * 获取所有账户列表
	 * @param page 分页搜索参数
	 * @return 账户列表
	 */
//	@PreAuthorize("hasAuthority('isp-ldap_account_list')")
	@RequestMapping(value = "/ldap/account/list", method = RequestMethod.POST)
	public Object listLdapAccount(@RequestBody LdapRequestPage<LdapAccountDTO> page) {
		LdapPage<LdapAccount> user = ldapAccountService.findAll(page.getSearch(), page.getNextTag(), page.getPageSize());
		LdapResultPage result = new LdapResultPage<>(user);
		result.setNextTag(user.getNextTag());
		result.setTotal(user.getTotal());
		return result;
	}

	/**
	 * 新增账户
	 * @param record 账户信息
	 * @return 账户信息
	 * @throws Exception 异常信息
	 */
	@PreAuthorize("hasAuthority('isp-ldap_account_create')")
	@RequestMapping(value = "/ldap/account/create", method = RequestMethod.POST)
	public Object createLdapAccount(@RequestBody LdapAccountDTO record) throws Exception {
		LdapAccount user = ldapAccountService.create(record);
		return JSONResult.success(user);
	}

	/**
	 * 更新账户信息
	 * @param record 账户信息
	 * @return 账户信息
	 */
	@PreAuthorize("hasAuthority('isp-ldap_account_update')")
	@RequestMapping(value = "/ldap/account/update", method = RequestMethod.POST)
	public Object updateLdapAccount(@RequestBody LdapAccountDTO record) {
		LdapAccount user = ldapAccountService.modifyLdapAccount(record);
		return JSONResult.success(user);
	}

	/**
	 * 删除账户
	 * @param serverId 服务器ID
	 * @param uid 账户UID
	 * @return 请求结果
	 */
	@PreAuthorize("hasAuthority('isp-ldap_account_delete')")
	@RequestMapping(value = "/ldap/account/delete", method = RequestMethod.GET)
	public Object deleteLdapAccount(@RequestParam Integer serverId, @RequestParam String uid) throws Exception {
		ldapAccountService.deleteLdapAccount(serverId, uid);
		return JSONResult.success();
	}

	/**
	 * 修改密码
	 * @param serverId 服务器ID
	 * @param uid 用户UID
	 * @param password 密码
	 * @return 处理结果
	 * @throws Exception 异常信息
	 */
	@PreAuthorize("hasAuthority('isp-ldap_account_password')")
	@RequestMapping(value = "/ldap/account/password", method = RequestMethod.GET)
	public Object changeLdapPassword(@RequestParam Integer serverId, @RequestParam String uid, @RequestParam String password) throws Exception {
		ldapAccountService.changePassword(serverId, uid, password);
		return JSONResult.success();
	}

	/**
	 * 根据cn获取组信息
	 * @param cn 组cn
	 * @return 组信息
	 */
	@PreAuthorize("hasAuthority('isp-ldap_group_get')")
	@RequestMapping(value = "/ldap/group/get", method = RequestMethod.GET)
	public Object findLdapGroupByCn(@RequestParam Integer serverId, @RequestParam String cn) {
		LdapGroup user = ldapAccountService.findGroup(serverId, cn);
		return JSONResult.success(user);
	}

	/**
	 * 获取所有组列表
	 * @param serverId 服务器ID
	 * @return 组列表
	 */
	@PreAuthorize("hasAuthority('isp-ldap_group_list')")
	@RequestMapping(value = "/ldap/group/list", method = RequestMethod.GET)
	public Object listLdapGroup(@RequestParam Integer serverId) {
		List<LdapGroup> user = ldapAccountService.findAllGroup(serverId);
		return JSONResult.success(user);
	}

	/**
	 * 新增组
	 * @param record 组信息
	 * @return 组信息
	 * @throws Exception 异常信息
	 */
	@PreAuthorize("hasAuthority('isp-ldap_group_create')")
	@RequestMapping(value = "/ldap/group/create", method = RequestMethod.POST)
	public Object createLdapGroup(@RequestBody LdapGroupDTO record) throws Exception {
		LdapGroup user = ldapAccountService.createGroup(record);
		return JSONResult.success(user);
	}

	/**
	 * 更新组信息
	 * @param record 组信息
	 * @return 组信息
	 */
	@PreAuthorize("hasAuthority('isp-ldap_group_update')")
	@RequestMapping(value = "/ldap/group/update", method = RequestMethod.POST)
	public Object updateLdapGroup(@RequestBody LdapGroupDTO record) {
		LdapGroup user = ldapAccountService.modifyGroup(record);
		return JSONResult.success(user);
	}

	/**
	 * 删除组
	 * @param serverId 服务器ID
	 * @param cn 组CN
	 * @return 请求结果
	 */
	@PreAuthorize("hasAuthority('isp-ldap_group_delete')")
	@RequestMapping(value = "/ldap/group/delete", method = RequestMethod.GET)
	public Object deleteLdapGroup(@RequestParam Integer serverId, @RequestParam String cn) {
		ldapAccountService.deleteGroup(serverId, cn);
		return JSONResult.success();
	}

	/**
	 * 根据cn获取Samba虚拟目录信息
	 * @param id 记录ID
	 * @return Samba虚拟目录信息
	 */
	@PreAuthorize("hasAuthority('isp-smb_vdir_get')")
	@RequestMapping(value = "/smb/vdir/get", method = RequestMethod.GET)
	public Object findSmbVDirById(@RequestParam Integer id) {
		IspSmbVDir ispSmbVDir = ispService.findSmbVDir(id);
		return JSONResult.success(ispSmbVDir);
	}

	/**
	 * 获取所有Samba虚拟目录列表
	 * @return Samba虚拟目录列表
	 */
	@PreAuthorize("hasAuthority('isp-smb_vdir_list')")
	@RequestMapping(value = "/smb/vdir/list", method = RequestMethod.POST)
	public Object listSmbVDir(@RequestBody JSONRequestPage<IspSmbVDir> page) {
		IspSmbVDir example = page.getSearch();
		if(example == null) {
			example = new IspSmbVDir();
		}
		example.setClientId(EsSecurityHandler.clientId());
		Page<IspSmbVDir> list = ispService.listSmbVDir(example, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(list.getResult());
		result.setPageNum(list.getPageNum());
		result.setTotal(list.getTotal());
		return result;
	}

	/**
	 * 新增Samba虚拟目录
	 * @param record Samba虚拟目录信息
	 * @return Samba虚拟目录信息
	 * @throws Exception 异常信息
	 */
	@PreAuthorize("hasAuthority('isp-smb_vdir_create')")
	@RequestMapping(value = "/smb/vdir/create", method = RequestMethod.POST)
	public Object createSmbVDir(@RequestBody IspSmbVDir record) throws Exception {
		record.setClientId(EsSecurityHandler.clientId());
		ispService.createSmbVDir(record);
		return JSONResult.success(record);
	}

	/**
	 * 更新Samba虚拟目录信息
	 * @param record Samba虚拟目录信息
	 * @return Samba虚拟目录信息
	 * @throws Exception 异常信息
	 */
	@PreAuthorize("hasAuthority('isp-smb_vdir_update')")
	@RequestMapping(value = "/smb/vdir/update", method = RequestMethod.POST)
	public Object updateSmbVDir(@RequestBody IspSmbVDir record) throws Exception {
		ispService.updateSmbVDir(record);
		return JSONResult.success(record);
	}

	/**
	 * 删除Samba虚拟目录
	 * @param id Samba虚拟目录id
	 * @return 请求结果
	 */
	@PreAuthorize("hasAuthority('isp-smb_vdir_delete')")
	@RequestMapping(value = "/smb/vdir/delete", method = RequestMethod.GET)
	public Object deleteSmbVDir(@RequestParam Integer id) throws Exception {
		ispService.deleteSmbVDir(id);
		return JSONResult.success();
	}
}