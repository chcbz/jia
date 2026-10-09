package cn.jia.oauth.api;

import cn.jia.oauth.security.TenantSelectionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;

/** The central login session, not a browser-provided tenant header, owns this selection. */
@Controller
@RequestMapping("/oauth2")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cyf.oauth.authorization-server.enabled",
        havingValue = "true", matchIfMissing = true)
public class TenantSelectionController {
    private final TenantSelectionService selectionService;

    @GetMapping(value = "/tenant-options", produces = MediaType.TEXT_HTML_VALUE)
    public ModelAndView page(@RequestParam("state") String state, HttpServletRequest request,
            HttpServletResponse response, Authentication authentication) {
        noStore(response);
        return view(selectionService.options(request.getSession(false), state, authentication));
    }

    @GetMapping(value = "/tenant-options", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public TenantSelectionService.Selection options(@RequestParam("authorizationRequestId") String id,
            HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        noStore(response);
        return selectionService.options(request.getSession(false), id, authentication);
    }

    @PostMapping(value = "/tenant-selection", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ModelAndView select(@RequestParam("authorizationRequestId") String id,
            @RequestParam("tenantId") String tenantId, HttpServletRequest request,
            HttpServletResponse response, Authentication authentication) {
        noStore(response);
        return view(selectionService.select(request.getSession(false), id, tenantId, authentication));
    }

    @ExceptionHandler(OAuth2AuthenticationException.class)
    public ResponseEntity<Map<String, String>> invalid(OAuth2AuthenticationException error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("error", error.getError().getErrorCode()));
    }

    private static ModelAndView view(TenantSelectionService.Selection selection) {
        ModelAndView view = new ModelAndView(selection.selected() == null
                ? "oauth/tenant-selection" : "oauth/authorize");
        view.addObject("clientId", selection.clientId());
        view.addObject("state", selection.authorizationRequestId());
        view.addObject("scopes", selection.scopes());
        view.addObject("options", selection.options());
        view.addObject("selected", selection.selected());
        return view;
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Referrer-Policy", "no-referrer");
    }
}
