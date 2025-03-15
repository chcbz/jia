package cn.jia.task.api;

import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.task.entity.TaskDetailEntity;
import cn.jia.task.entity.TaskDetailVO;
import cn.jia.task.entity.TaskPlanEntity;
import cn.jia.task.entity.TaskPlanVO;
import cn.jia.task.service.TaskService;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * 任务计划接口
 */
@RestController
@RequestMapping("/task")
public class TaskController {

    @Autowired
    private TaskService taskService;

    /**
     * 获取任务信息
     *
     * @param id 任务ID
     * @return JsonResult
     */
    /*@PreAuthorize("hasAuthority('task-get')")*/
    @RequestMapping(value = "/get", method = RequestMethod.GET)
    public Object findById(@RequestParam(name = "id") Long id) throws Exception {
        TaskPlanEntity task = taskService.get(id);
        return JsonResult.success(task);
    }

    /**
     * 创建任务
     *
     * @param task 任务信息
     * @return JsonResult
     */
    /*@PreAuthorize("hasAuthority('task-create')")*/
    @RequestMapping(value = "/create", method = RequestMethod.POST)
    public Object create(@RequestBody TaskPlanEntity task) {
        task.setClientId(EsContextHolder.getContext().getClientId());
        taskService.create(task);
        return JsonResult.success();
    }

    /**
     * 更新任务信息
     *
     * @param task 任务信息
     * @return JsonResult
     */
    /*@PreAuthorize("hasAuthority('task-update')")*/
    @RequestMapping(value = "/update", method = RequestMethod.POST)
    public Object update(@RequestBody TaskPlanEntity task) {
        taskService.update(task);
        return JsonResult.success();
    }

    /**
     * 删除任务
     *
     * @param id 任务ID
     * @return JsonResult
     */
    /*@PreAuthorize("hasAuthority('task-delete')")*/
    @RequestMapping(value = "/delete", method = RequestMethod.GET)
    public Object delete(@RequestParam(name = "id") Long id) {
        taskService.delete(id);
        return JsonResult.success();
    }

    /**
     * 取消任务
     *
     * @param id 任务ID
     * @return JsonResult
     */
    /*@PreAuthorize("hasAuthority('task-cancel')")*/
    @RequestMapping(value = "/cancel", method = RequestMethod.GET)
    public Object cancel(@RequestParam(name = "id") Long id) {
        taskService.cancel(id);
        return JsonResult.success();
    }

    /**
     * 获取所有任务信息
     *
     * @return 任务列表
     */
    /*@PreAuthorize("hasAuthority('task-search')")*/
    @RequestMapping(value = "/search", method = RequestMethod.POST)
    public Object search(@RequestBody JsonRequestPage<TaskPlanVO> page) {
        TaskPlanVO plan = Optional.ofNullable(page.getSearch()).orElse(new TaskPlanVO());
        plan.setClientId(EsContextHolder.getContext().getClientId());
        PageInfo<TaskPlanEntity> taskList = taskService.findPage(plan, page.getPageSize(), page.getPageNum());
        JsonResultPage<TaskPlanEntity> result = new JsonResultPage<>(taskList.getList());
        result.setPageNum(taskList.getPageNum());
        result.setTotal(taskList.getTotal());
        return result;
    }

    /**
     * 获取所有任务信息
     *
     * @return 任务明细列表
     */
    @RequestMapping(value = "/item/search", method = RequestMethod.POST)
    public Object searchItem(@RequestBody JsonRequestPage<TaskDetailVO> page) {
        TaskDetailVO item = Optional.ofNullable(page.getSearch()).orElse(new TaskDetailVO());
        PageInfo<TaskDetailEntity> taskList = taskService.findItems(item, page.getPageNum(), page.getPageSize());
        JsonResultPage<TaskDetailEntity> result = new JsonResultPage<>(taskList.getList());
        result.setPageNum(taskList.getPageNum());
        result.setTotal(taskList.getTotal());
        return result;
    }
}
