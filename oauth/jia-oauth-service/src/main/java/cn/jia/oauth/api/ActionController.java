package cn.jia.oauth.api;

import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.util.JsonUtil;
import cn.jia.oauth.entity.OauthActionEntity;
import cn.jia.oauth.service.ActionService;
import com.github.pagehelper.PageInfo;
import jakarta.inject.Inject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/action")
public class ActionController {

    @Inject
    private ActionService actionService;

    /**
     * 获取资源信息
     *
     * @param id 资源ID
     * @return 资源信息
     */
    /*@PreAuthorize("hasAuthority('action-get')")*/
    @RequestMapping(value = "/get", method = RequestMethod.GET)
    public Object findById(@RequestParam(name = "id") Integer id) {
        OauthActionEntity action = actionService.get(id);
        return JsonResult.success(action);
    }

    /**
     * 创建资源
     *
     * @param action 资源信息
     * @return 资源信息
     */
    @PreAuthorize("hasAuthority('action-create')")
    @RequestMapping(value = "/create", method = RequestMethod.POST)
    public Object create(@RequestBody OauthActionEntity action) {
        actionService.create(action);
        return JsonResult.success();
    }

    /**
     * 更新资源信息
     *
     * @param action 资源信息
     * @return 资源信息
     */
    @PreAuthorize("hasAuthority('action-update')")
    @RequestMapping(value = "/update", method = RequestMethod.POST)
    public Object update(@RequestBody OauthActionEntity action) {
        actionService.update(action);
        return JsonResult.success();
    }

    /**
     * 刷新整个模块的资源
     *
     * @param actionList 资源列表
     * @return 资源列表
     */
    @PreAuthorize("hasAuthority('action-refresh')")
    @RequestMapping(value = "/refresh", method = RequestMethod.POST)
    public Object refresh(@RequestBody List<OauthActionEntity> actionList) {
        actionService.refresh(actionList);
        return JsonResult.success();
    }

    /**
     * 删除资源
     *
     * @param id 资源ID
     * @return 资源信息
     */
    @PreAuthorize("hasAuthority('action-delete')")
    @RequestMapping(value = "/delete", method = RequestMethod.GET)
    public Object delete(@RequestParam(name = "id") Integer id) {
        actionService.delete(id);
        return JsonResult.success();
    }

    /**
     * 获取所有资源信息
     *
     * @return 资源列表
     */
    /*@PreAuthorize("hasAuthority('action-list')")*/
    @RequestMapping(value = "/list", method = RequestMethod.POST)
    public Object list(@RequestBody JsonRequestPage<String> page) {
        OauthActionEntity action = JsonUtil.fromJson(page.getSearch(), OauthActionEntity.class);
        if (action == null) {
            action = new OauthActionEntity();
        }
        action.setResourceId("jia-api-admin");
        PageInfo<OauthActionEntity> actionList = actionService.findPage(action, page.getPageSize(), page.getPageNum());
        JsonResultPage<OauthActionEntity> result = new JsonResultPage<>(actionList.getList());
        result.setPageNum(actionList.getPageNum());
        result.setTotal(actionList.getTotal());
        return result;
    }
}
