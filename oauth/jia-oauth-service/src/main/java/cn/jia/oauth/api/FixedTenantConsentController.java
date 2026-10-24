package cn.jia.oauth.api;

import cn.jia.oauth.security.FixedTenantConsentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;

@Controller
@RequiredArgsConstructor
@ConditionalOnExpression("'${cyf.oauth.authorization-server.enabled:true}' == 'true' && "
        + "'${cyf.tenant.admission-mode:platform}' == 'fixed-tenant'")
public final class FixedTenantConsentController {
    private final FixedTenantConsentService consentService;

    @GetMapping(value = "/oauth2/fixed-consent", produces = MediaType.TEXT_HTML_VALUE)
    public ModelAndView page(@RequestParam("state") String state, HttpServletRequest request,
            HttpServletResponse response, Authentication authentication) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Pragma", "no-cache");
        // Keep state out of Referer without making the browser's consent POST
        // use Origin: null, which the existing strict CORS policy rejects.
        response.setHeader("Referrer-Policy", "strict-origin");
        FixedTenantConsentService.Consent consent = consentService.view(
                request.getSession(false), state, authentication);
        ModelAndView view = new ModelAndView("oauth/authorize");
        view.addObject("clientId", consent.clientId());
        view.addObject("state", consent.state());
        view.addObject("scopes", consent.scopes());
        return view;
    }

    @ExceptionHandler(OAuth2AuthenticationException.class)
    public ResponseEntity<Map<String, String>> invalid(OAuth2AuthenticationException error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("error", error.getError().getErrorCode()));
    }
}
