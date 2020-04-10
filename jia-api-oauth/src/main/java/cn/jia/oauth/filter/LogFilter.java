package cn.jia.oauth.filter;

import javax.servlet.annotation.WebFilter;
import javax.servlet.annotation.WebInitParam;

import cn.jia.core.filter.EsLogFilter;

@WebFilter(filterName = "logFilter", urlPatterns = "/*",
initParams = {
    @WebInitParam(name = "exclusions", value = "*.js,*.gif,*.jpg,*.bmp,*.png,*.css,*.ico,/druid/*")//忽略资源
}
)
public class LogFilter extends EsLogFilter{

}
