package cn.jia.user.api;

import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.user.entity.PermsEntity;
import cn.jia.user.service.PermsService;
import com.github.pagehelper.PageInfo;
import jakarta.inject.Inject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/action")
public class PermsController {

    @Inject
    private PermsService permsService;

    /**
     * 获取资源信息
     *
     * @param id 资源ID
     * @return 资源信息
     */
    /*@PreAuthorize("hasAuthority('action-get')")*/
    @RequestMapping(value = "/get", method = RequestMethod.GET)
    public Object findById(@RequestParam(name = "id") Integer id) {
        PermsEntity action = permsService.get(id);
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
    public Object create(@RequestBody PermsEntity action) {
        permsService.create(action);
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
    public Object update(@RequestBody PermsEntity action) {
        permsService.update(action);
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
    public Object refresh(@RequestBody List<PermsEntity> actionList) {
        permsService.refresh(actionList);
        return JsonResult.success();
    }

    /**
     * 删除资源
     *
     * @param id 资源ID
     * @return 资源信息
     */
    @PreAuthorize("hasAuthority('action-delete')")
    @RequestMapping(value = "/delete", method = RequestMethod.DELETE)
    public Object delete(@RequestParam(name = "id") Integer id) {
        permsService.delete(id);
        return JsonResult.success();
    }

    /**
     * 获取所有资源信息
     *
     * @return 资源列表
     */
    /*@PreAuthorize("hasAuthority('action-list')")*/
    @RequestMapping(value = "/list", method = RequestMethod.POST)
    public Object list(@RequestBody JsonRequestPage<PermsEntity> page) {
        PermsEntity action = Optional.ofNullable(page.getSearch()).orElse(new PermsEntity());
        PageInfo<PermsEntity> actionList = permsService.findPage(action, page.getPageSize(), page.getPageNum(), page.getOrderBy());
        JsonResultPage<PermsEntity> result = new JsonResultPage<>(actionList.getList());
        result.setPageNum(actionList.getPageNum());
        result.setTotal(actionList.getTotal());
        return result;
    }
}
