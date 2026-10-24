package cn.jia.oauth.filter;

import cn.jia.core.tenant.TrustedTenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Clears pooled-thread tenant state before authentication can short-circuit a request. */
public final class TrustedTenantContextCleanupFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        TrustedTenantContext.clear();
        try {
            chain.doFilter(request, response);
        } finally {
            TrustedTenantContext.clear();
        }
    }

    @Override
    protected void doFilterNestedErrorDispatch(HttpServletRequest request,
                                               HttpServletResponse response,
                                               FilterChain chain)
            throws ServletException, IOException {
        try {
            // A container may dispatch ERROR before the outer REQUEST unwinds. Hide that
            // request identity from error handlers, then restore it only for legitimate
            // continuation of the still-running outer request.
            TrustedTenantContext.runWithoutScopeChecked(() -> chain.doFilter(request, response));
        } catch (IOException | ServletException known) {
            throw known;
        } catch (RuntimeException unchecked) {
            throw unchecked;
        } catch (Exception impossible) {
            throw new ServletException(impossible);
        }
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
