package cn.jia.oauth.api;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.JsonUtil;
import cn.jia.oauth.entity.OauthClientEntity;
import cn.jia.oauth.entity.OauthResourceEntity;
import cn.jia.oauth.service.ClientService;
import cn.jia.oauth.service.ResourceService;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/oauth")
public class OauthController {

    @Autowired
    private ClientService clientService;
    @Autowired
    private ResourceService resourceService;

    /**
     * 根据应用标识符获取客户ID
     *
     * @param appcn
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/clientid", method = RequestMethod.GET)
    public Object findClientId(@RequestParam(name = "appcn") String appcn) throws Exception {
        OauthClientEntity client = clientService.findByAppcn(appcn);
        if (client == null) {
            throw new EsRuntimeException(EsErrorConstants.DATA_NOT_FOUND);
        }
        return JsonResult.success(client.getClientId());
    }

    /**
     * 获取客户端信息
     *
     * @return
     */
    @RequestMapping(value = "/client/get", method = RequestMethod.GET)
    public Object find() {
        OauthClientEntity client = clientService.get(EsSecurityHandler.clientId());
        return JsonResult.success(client);
    }

    /**
     * 更新客户端信息
     *
     * @param client
     * @return
     */
    @RequestMapping(value = "/client/update", method = RequestMethod.POST)
    public Object updateClient(@RequestBody OauthClientEntity client) {
        client.setClientId(EsSecurityHandler.clientId());
        clientService.update(client);
        return JsonResult.success(client);
    }

//	/**
//	 * 添加客户端资源
//	 * @param resourceId
//	 * @return
//	 */
//	@RequestMapping(value = "/client/addresource", method = RequestMethod.GET)
//	public Object addResource(@RequestParam(name = "resourceId") String resourceId) {
//		clientService.addResource(resourceId, EsSecurityHandler.clientId());
//		return JsonResult.success();
//	}

    /**
     * 获取资源
     *
     * @param resourceId
     * @return
     */
    @PreAuthorize("hasAuthority('oauth-resource_get')")
    @RequestMapping(value = "/resource/get", method = RequestMethod.GET)
    public Object findResourceById(@RequestParam String resourceId) throws Exception {
        OauthResourceEntity resource = resourceService.get(resourceId);
        return JsonResult.success(resource);
    }

    /**
     * 创建资源
     *
     * @param resource
     * @return
     */
    @PreAuthorize("hasAuthority('oauth-resource_create')")
    @RequestMapping(value = "/resource/create", method = RequestMethod.POST)
    public Object createResource(@RequestBody OauthResourceEntity resource) {
        resourceService.create(resource);
        return JsonResult.success();
    }

    /**
     * 更新资源信息
     *
     * @param resource
     * @return
     */
    @PreAuthorize("hasAuthority('oauth-resource_update')")
    @RequestMapping(value = "/resource/update", method = RequestMethod.POST)
    public Object updateResource(@RequestBody OauthResourceEntity resource) {
        resourceService.update(resource);
        return JsonResult.success();
    }

    /**
     * 删除资源
     *
     * @param resourceId
     * @return
     */
    @PreAuthorize("hasAuthority('oauth-resource_delete')")
    @RequestMapping(value = "/resource/delete", method = RequestMethod.GET)
    public Object delete(@RequestParam String resourceId) {
        resourceService.delete(resourceId);
        return JsonResult.success();
    }

    /**
     * 获取所有资源
     *
     * @return
     */
    @PreAuthorize("hasAuthority('oauth-resource_list')")
    @RequestMapping(value = "/resource/list", method = RequestMethod.POST)
    public Object list(@RequestBody JsonRequestPage<String> page) {
        OauthResourceEntity example = JsonUtil.fromJson(page.getSearch(), OauthResourceEntity.class);
        PageInfo<OauthResourceEntity> resourceList = resourceService.findPage(example, page.getPageSize(),
                page.getPageNum());
        JsonResultPage<OauthResourceEntity> result = new JsonResultPage<>(resourceList.getList());
        result.setPageNum(resourceList.getPageNum());
        result.setTotal(resourceList.getTotal());
        return result;
    }

    /**
     * 获取用户OAUTH2权限信息
     *
     * @param user 用户信息
     * @return 用户信息
     */
    @RequestMapping(value = "/user", method = RequestMethod.GET)
    public Principal info(Principal user) {
        return user;
    }
}
