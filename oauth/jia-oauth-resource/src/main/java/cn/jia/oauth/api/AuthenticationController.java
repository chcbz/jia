package cn.jia.oauth.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthenticationController {

    @GetMapping("/resource")
    @ResponseBody
    public Object token() {
        //通过OAuth2AuthorizedClient对象获取到客户端和token令牌相关的信息，然后直接返回给前端页面
        return null;
    }
}
