package cn.jia.isp.api;

import cn.jia.core.config.SpringContextHolder;
import cn.jia.core.util.StreamUtil;
import cn.jia.isp.entity.IspFileEntity;
import cn.jia.isp.service.FileService;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.HtmlUtils;

import java.io.FileInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 文件控制器类，处理与文件相关的HTTP请求
 */
@RestController
@RequestMapping("/file")
public class FileController {

	/**
	 * 文件服务，用于执行文件相关操作
	 */
	@Inject
	private FileService fileService;

	/**
	 * 根据URI获取资源的字节码数组
	 *
	 * @param uri 请求路径中的URI部分，用于定位资源
	 * @param request HTTP请求对象，用于获取请求路径信息
	 * @param response HTTP响应对象，用于设置响应字符编码
	 * @return 资源的字节码数组，如果资源不存在则返回null
	 * @throws Exception 如果文件读取过程中发生错误
	 */
	@RequestMapping(value = "/res/{uri:.+}/**", method = RequestMethod.GET)
	@ResponseBody
	public byte[] findByURI(@PathVariable String uri, HttpServletRequest request, HttpServletResponse response) throws Exception {
		// 获取请求路径
		final String path = request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE).toString();
		// 获取最佳匹配模式
		final String bestMatchingPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE).toString();
		// 提取路径中的参数
		String arguments = new AntPathMatcher().extractPathWithinPattern(bestMatchingPattern, path);

		// 如果存在额外参数，将其添加到URI中
		if (!arguments.isEmpty()) {
			uri = uri + '/' + arguments;
		}
		// 对URI进行解码和HTML反转义
		uri = HtmlUtils.htmlUnescape(URLDecoder.decode(uri, StandardCharsets.UTF_8));
		// 设置响应字符编码
		response.setCharacterEncoding(request.getCharacterEncoding());
		// 根据URI查找文件记录
		IspFileEntity record = fileService.findByUri(uri);
		if(record != null) {
			// 获取文件路径配置
			String filePath = SpringContextHolder.getProperty("jia.file.path", String.class);
			// 构建文件完整路径
			java.io.File file = new java.io.File(filePath + "/" + uri);
			// 读取文件内容到字节码数组
			FileInputStream fis = new FileInputStream(file);
			byte[] b = StreamUtil.toByteArray(fis);
			fis.close();
			return b;
		}
		return null;
	}

}
