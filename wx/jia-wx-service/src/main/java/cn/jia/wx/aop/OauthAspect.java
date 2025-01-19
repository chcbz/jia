package cn.jia.wx.aop;

import cn.jia.core.entity.JsonResult;
import cn.jia.wx.entity.MpUserEntity;
import cn.jia.wx.service.MpUserService;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import me.chanjar.weixin.common.bean.oauth2.WxOAuth2AccessToken;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class OauthAspect {
    @Autowired
    private MpUserService mpUserService;

    @Around("execution(* cn.jia.wx.api.WxMpController.oauth2AccessToken(..))")
    public Object getOathTokenByWxToken(ProceedingJoinPoint joinPoint) throws Throwable {
        JsonResult<WxOAuth2AccessToken> obj = (JsonResult<WxOAuth2AccessToken>)joinPoint.proceed(joinPoint.getArgs());
        WxOAuth2AccessTokenWrapper accessToken = WxOAuth2AccessTokenWrapper.clone(obj.getData());
        String wxToken = accessToken.getAccessToken();
        String openId = accessToken.getOpenId();
        MpUserEntity mpUserEntity = mpUserService.findByOpenId(openId);
        obj.setData(accessToken);
        return obj;
    }

    @Getter
    @Setter
    @ToString(callSuper = true)
    static class WxOAuth2AccessTokenWrapper extends WxOAuth2AccessToken {
        private String oathToken;

        public static WxOAuth2AccessTokenWrapper clone(WxOAuth2AccessToken accessToken) {
            WxOAuth2AccessTokenWrapper accessTokenWrapper = new WxOAuth2AccessTokenWrapper();
            accessTokenWrapper.setAccessToken(accessToken.getAccessToken());
            accessTokenWrapper.setRefreshToken(accessToken.getRefreshToken());
            accessTokenWrapper.setScope(accessToken.getScope());
            accessTokenWrapper.setOpenId(accessToken.getOpenId());
            accessTokenWrapper.setExpiresIn(accessToken.getExpiresIn());
            accessTokenWrapper.setSnapshotUser(accessToken.getSnapshotUser());
            accessTokenWrapper.setUnionId(accessToken.getUnionId());
            return accessTokenWrapper;
        }
    }
}
