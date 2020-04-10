package cn.jia.oauth.api;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.JSONRequestPage;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.entity.JSONResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.BeanUtil;
import cn.jia.core.util.DataUtil;
import cn.jia.core.util.JSONUtil;
import cn.jia.isp.entity.LdapUser;
import cn.jia.isp.entity.LdapUserGroup;
import cn.jia.isp.service.LdapUserGroupService;
import cn.jia.isp.service.LdapUserService;
import cn.jia.oauth.entity.Client;
import cn.jia.oauth.entity.Resource;
import cn.jia.oauth.service.ClientService;
import cn.jia.oauth.service.ResourceService;
import cn.jia.user.common.ErrorConstants;
import cn.jia.user.entity.ClientRegister;
import cn.jia.user.entity.Org;
import cn.jia.user.entity.User;
import cn.jia.user.service.OrgService;
import cn.jia.user.service.UserService;
import com.github.pagehelper.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.naming.Name;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

@RestController
@RequestMapping("/oauth")
public class OAuthController {
	
	@Autowired
	private ClientService clientService;
	@Autowired
	private OrgService orgService;
	@Autowired
	private UserService userService;
	@Autowired
	private ResourceService resourceService;
	@Autowired
	private LdapUserService ldapUserService;
	@Autowired
	private LdapUserGroupService ldapUserGroupService;
	@Autowired
	private LdapTemplate ldapTemplate;

	/**
	 * 根据应用标识码获取客户ID
	 * @param appcn
	 * @return
	 * @throws Exception
	 */
	@RequestMapping(value = "/clientid", method = RequestMethod.GET)
	public Object findClientId(@RequestParam(name = "appcn") String appcn) throws Exception {
		Client client = clientService.findByAppcn(appcn);
		if(client == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		return JSONResult.success(client.getClientId());
	}
	
	/**
	 * 获取客户端信息
	 * @return
	 */
	@RequestMapping(value = "/client/get", method = RequestMethod.GET)
	public Object find() {
		Client client = clientService.find(EsSecurityHandler.clientId());
		return JSONResult.success(client);
	}
	
	/**
	 * 更新客户端信息
	 * @param client
	 * @return
	 */
	@RequestMapping(value = "/client/update", method = RequestMethod.POST)
	public Object updateClient(@RequestBody Client client) {
		client.setClientId(EsSecurityHandler.clientId());
		clientService.update(client);
		return JSONResult.success(client);
	}

	/**
	 * 添加客户端资源
	 * @param resourceId
	 * @return
	 */
	@RequestMapping(value = "/client/addresource", method = RequestMethod.GET)
	public Object addResource(@RequestParam(name = "resourceId") String resourceId) {
		clientService.addResource(resourceId, EsSecurityHandler.clientId());
		return JSONResult.success();
	}
	
	/**
	 * 注册客户端
	 * @param register
	 * @return
	 * @throws Exception
	 */
	@RequestMapping(value = "/client/register", method = RequestMethod.POST)
	public Object registerClient(@RequestBody ClientRegister register) throws Exception {
		Org org = orgService.findByCode(register.getOrgCode());
		if(org != null) {
			throw new EsRuntimeException(ErrorConstants.ORG_HAS_EXIST);
		}
		User user = userService.findByUsername(register.getUsername());
		if(user != null) {
			throw new EsRuntimeException(ErrorConstants.USER_HAS_EXIST);
		}
		user = userService.findByPhone(register.getPhone());
		if(user != null) {
			throw new EsRuntimeException(ErrorConstants.USER_PHONE_HAS_EXIST);
		}

        //创建客户端信息
        Client client = new Client();
        client.setClientId("jia"+ DataUtil.getRandom(false, 15));
        client.setClientSecret(DataUtil.getRandom(false, 32));
        client.setAppcn(register.getOrgCode());
        clientService.create(client);

        LdapUserGroup group = ldapUserGroupService.findByCn(register.getOrgCode());
        if(group == null){
            group = new LdapUserGroup(register.getOrgCode());
            group.setClientId(client.getClientId());
            group.setName(register.getOrgName());
            group.setRemark(register.getRemark());
            LdapUser ldapUser = ldapUserService.findByUid("admin");
            if(ldapUser == null){
                throw new EsRuntimeException();
            }
            final LdapContextSource contextSource = (LdapContextSource)ldapTemplate.getContextSource();
            Set<Name> member = new HashSet<>();
            member.add(contextSource.getBaseLdapName().addAll(ldapUser.getDn()));
            group.setMember(member);
            ldapUserGroupService.create(group);
        }
        //创建组织信息
        org = new Org();
        org.setClientId(client.getClientId());
        org.setName(register.getOrgName());
        org.setCode(register.getOrgCode());
        orgService.create(org);
        //创建用户信息
        user = new User();
        BeanUtil.copyPropertiesIgnoreNull(register, user, "id");
        user.setPosition(org.getId());
        userService.create(user);
        //绑定默认角色
        user.setRoleIds(new ArrayList<Integer>(){{add(1);}});
        userService.changeRole(user, client.getClientId());
        //绑定组织
		Org finalOrg = org;
		user.setOrgIds(new ArrayList<Integer>(){{add(finalOrg.getId());}});
        userService.changeOrg(user);
        return JSONResult.success(client);
	}

	/**
	 * 获取资源
	 * @param resourceId
	 * @return
	 */
	@PreAuthorize("hasAuthority('oauth-resource_get')")
	@RequestMapping(value = "/resource/get", method = RequestMethod.GET)
	public Object findResourceById(@RequestParam String resourceId) throws Exception {
		Resource resource = resourceService.find(resourceId);
		return JSONResult.success(resource);
	}

	/**
	 * 创建资源
	 * @param resource
	 * @return
	 */
	@PreAuthorize("hasAuthority('oauth-resource_create')")
	@RequestMapping(value = "/resource/create", method = RequestMethod.POST)
	public Object createResource(@RequestBody Resource resource) {
		resourceService.create(resource);
		return JSONResult.success();
	}

	/**
	 * 更新资源信息
	 * @param resource
	 * @return
	 */
	@PreAuthorize("hasAuthority('oauth-resource_update')")
	@RequestMapping(value = "/resource/update", method = RequestMethod.POST)
	public Object updateResource(@RequestBody Resource resource) {
		resourceService.update(resource);
		return JSONResult.success();
	}

	/**
	 * 删除资源
	 * @param resourceId
	 * @return
	 */
	@PreAuthorize("hasAuthority('oauth-resource_delete')")
	@RequestMapping(value = "/resource/delete", method = RequestMethod.GET)
	public Object delete(@RequestParam String resourceId) {
		resourceService.delete(resourceId);
		return JSONResult.success();
	}

	/**
	 * 获取所有资源
	 * @return
	 */
	@PreAuthorize("hasAuthority('oauth-resource_list')")
	@RequestMapping(value = "/resource/list", method = RequestMethod.POST)
	public Object list(@RequestBody JSONRequestPage<String> page) {
		Resource example = JSONUtil.fromJson(page.getSearch(), Resource.class);
		Page<Resource> resourceList = resourceService.list(example, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(resourceList.getResult());
		result.setPageNum(resourceList.getPageNum());
		result.setTotal(resourceList.getTotal());
		return result;
	}
}
