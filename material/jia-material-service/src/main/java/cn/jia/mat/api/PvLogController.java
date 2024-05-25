package cn.jia.mat.api;

import cn.jia.core.annotation.SysPermission;
import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.BeanUtil;
import cn.jia.core.util.HttpUtil;
import cn.jia.core.util.StringUtil;
import cn.jia.mat.common.MatErrorConstants;
import cn.jia.mat.entity.MatPvLogEntity;
import cn.jia.mat.service.MatPvLogService;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.UserService;
import com.github.pagehelper.PageInfo;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/pvlog")
@SysPermission(name = "material")
@Slf4j
public class PvLogController {
	
	@Autowired
	private MatPvLogService pvLogService;

	@Autowired(required = false)
	private UserService userService;

	/**
	 * 获取日志信息
	 * @param id 日志ID
	 * @return 日志信息
	 */
	@PreAuthorize("hasAuthority('pvlog-get')")
	@RequestMapping(value = "/get", method = RequestMethod.GET)
	public Object findById(@RequestParam(name = "id") Long id) throws Exception{
		MatPvLogEntity pageViewLog = pvLogService.get(id);
		if(pageViewLog == null) {
			throw new EsRuntimeException(MatErrorConstants.MEDIA_NOT_EXIST);
		}
		return JsonResult.success(pageViewLog);
	}

	/**
	 * 创建日志
	 * @param pageViewLog 日志信息
	 * @return 日志信息
	 */
	@PreAuthorize("hasAuthority('pvlog-create')")
	@RequestMapping(value = "/create", method = RequestMethod.POST)
	public Object create(@RequestBody MatPvLogEntity pageViewLog, HttpServletRequest request) {
		if(StringUtil.isNotBlank(pageViewLog.getJiacn())) {
			UserEntity user = userService.findByJiacn(pageViewLog.getJiacn());
			if(user != null) {
				BeanUtil.copyPropertiesIgnoreNull(user, pageViewLog, "id");
			}
		}
		pageViewLog.setIp(HttpUtil.getIpAddr(request));
		pageViewLog.setUri(request.getRequestURI());
		pageViewLog.setUserAgent(request.getHeader("user-agent"));
		pvLogService.create(pageViewLog);
		return JsonResult.success(pageViewLog);
	}

	/**
	 * 更新日志信息
	 * @param pageViewLog 日志信息
	 * @return 日志信息
	 */
	@PreAuthorize("hasAuthority('pvlog-update')")
	@RequestMapping(value = "/update", method = RequestMethod.POST)
	public Object update(@RequestBody MatPvLogEntity pageViewLog) {
		pvLogService.update(pageViewLog);
		return JsonResult.success();
	}

	/**
	 * 删除日志
	 * @param id 日志ID
	 * @return 处理结果
	 */
	@PreAuthorize("hasAuthority('pvlog-delete')")
	@RequestMapping(value = "/delete", method = RequestMethod.GET)
	public Object delete(@RequestParam(name = "id") Long id) {
		pvLogService.delete(id);
		return JsonResult.success();
	}
	
	/**
	 * 获取所有日志信息
	 * @return 日志列表
	 */
	@PreAuthorize("hasAuthority('pvlog-list')")
	@RequestMapping(value = "/list", method = RequestMethod.POST)
	public Object list(@RequestBody JsonRequestPage<MatPvLogEntity> page) {
        MatPvLogEntity example = page.getSearch() == null ? new MatPvLogEntity() : page.getSearch();
        example.setClientId(EsSecurityHandler.clientId());
		PageInfo<MatPvLogEntity> pageViewLogList = pvLogService.findPage(example, page.getPageSize(), page.getPageNum());
		JsonResultPage<MatPvLogEntity> result = new JsonResultPage<>(pageViewLogList.getList());
		result.setPageNum(pageViewLogList.getPageNum());
		result.setTotal(pageViewLogList.getTotal());
		return result;
	}
}
