package cn.jia.admin.filter;

import cn.jia.core.filter.EsLogFilter;

import javax.servlet.annotation.WebFilter;
import javax.servlet.annotation.WebInitParam;

@WebFilter(filterName = "logFilter", urlPatterns = "/*",
initParams = {
    @WebInitParam(name = "exclusions", value = "*.js,*.gif,*.jpg,*.bmp,*.png,*.css,*.ico,/druid/*")//忽略资源
}
)
public class LogFilter extends EsLogFilter{

}
