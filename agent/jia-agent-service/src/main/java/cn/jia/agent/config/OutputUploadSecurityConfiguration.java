package cn.jia.agent.config;

import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputRunAuthorizationService;
import cn.jia.agent.output.OutputTicketAuthorization;
import cn.jia.agent.api.OutputHttpEnvelope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Configuration(proxyBeanMethods=false)
@Conditional(OutputDeliveryEnabledCondition.class)
public class OutputUploadSecurityConfiguration {
    @Bean @Order(0)
    public SecurityFilterChain outputUploadSecurityFilterChain(HttpSecurity http,OutputRunAuthorizationService authorization)throws Exception{
        OutputTicketFilter filter=new OutputTicketFilter(authorization);
        http.securityMatcher(OutputUploadSecurityConfiguration::matches)
                .authorizeHttpRequests(a->a.anyRequest().permitAll())
                .addFilterBefore(filter, AnonymousAuthenticationFilter.class)
                .exceptionHandling(e->e.authenticationEntryPoint((r,s,x)->authorizationError(r,s,"OUTPUT_AUTH_UNAUTHORIZED",401,false)))
                .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(c->c.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(c->c.requestCache(new NullRequestCache())).csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }
    static boolean matches(HttpServletRequest r){String m=r.getMethod(),p=r.getRequestURI();
        if(("GET".equals(m)||"POST".equals(m))
                &&p.matches("/agent/tasks/[^/]+/work-items/[^/]+/lease(?:/(?:claim|start|heartbeat|release))?"))return true;
        if("POST".equals(m)&&(p.matches("/agent/tasks/[^/]+/output-publications")
                ||p.matches("/chat/conversations/[^/]+/outputs")))return true;
        if("POST".equals(m)&&p.matches("/agent/tasks/[^/]+/deliveries"))return true;
        if("POST".equals(m)&&"/agent/output-uploads".equals(p))return true;
        if(!p.matches("/agent/output-uploads/[^/]+(?:/content|/complete)?"))return false;
        return ("GET".equals(m)&&!p.endsWith("/content")&&!p.endsWith("/complete"))
                ||("PUT".equals(m)&&p.endsWith("/content"))
                ||("POST".equals(m)&&p.endsWith("/complete"));}
    private static void authorizationError(HttpServletRequest request,HttpServletResponse response,String code,int status,boolean retryable)throws IOException{OutputHttpEnvelope.writeError(request,response,code,status==503?"Output authorization unavailable":"Output access is unavailable",status,retryable);}
    static final class OutputTicketFilter extends OncePerRequestFilter{
        private final OutputRunAuthorizationService authorization;OutputTicketFilter(OutputRunAuthorizationService a){authorization=a;}
        @Override protected boolean shouldNotFilter(HttpServletRequest r){return !matches(r);}
        @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain chain)throws ServletException,IOException{
            OutputTicketAuthorization auth;
            try{
                String h=r.getHeader(HttpHeaders.AUTHORIZATION);
                if(h==null||!h.startsWith("Bearer ")){authorizationError(r,s,"OUTPUT_AUTH_UNAUTHORIZED",401,false);return;}
                boolean publish="POST".equals(r.getMethod())&&(r.getRequestURI().matches("/agent/tasks/[^/]+/output-publications")
                        ||r.getRequestURI().matches("/chat/conversations/[^/]+/outputs"));
                boolean lease="POST".equals(r.getMethod())&&r.getRequestURI()
                        .matches("/agent/tasks/[^/]+/work-items/[^/]+/lease/(?:claim|start|heartbeat|release)");
                boolean submit="POST".equals(r.getMethod())&&r.getRequestURI()
                        .matches("/agent/tasks/[^/]+/deliveries");
                auth=authorization.authorizeTicket(
                        h.substring(7),OutputConstants.OP_STATUS,true);
                if((publish||lease||submit)&&!jsonContentType(r)){
                    OutputHttpEnvelope.writeError(r,s,"OUTPUT_MIME_UNSUPPORTED",
                            "Output MIME unsupported",415,false);return;
                }
                if("PUT".equals(r.getMethod())&&(!OutputConstants.RUN_ACTIVE.equals(auth.runState())||!auth.operations().contains(OutputConstants.OP_UPLOAD))){authorizationError(r,s,"OUTPUT_AUTH_FORBIDDEN",403,false);return;}
            }catch(cn.jia.agent.output.OutputAuthorizationException denied){SecurityContextHolder.clearContext();int status="OUTPUT_AUTH_UNAUTHORIZED".equals(denied.getCode())?401:403;authorizationError(r,s,denied.getCode(),status,false);return;}
            catch(RuntimeException unavailable){SecurityContextHolder.clearContext();authorizationError(r,s,"OUTPUT_AUTH_UNAVAILABLE",503,true);return;}
            UsernamePasswordAuthenticationToken token=new UsernamePasswordAuthenticationToken(auth,null,List.of(new SimpleGrantedAuthority("output-ticket")));
            SecurityContextHolder.getContext().setAuthentication(token);
            chain.doFilter(r,s);
        }
        private static boolean jsonContentType(HttpServletRequest request){
            String value=request.getContentType();if(value==null)return false;
            try{return MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(value));}
            catch(RuntimeException invalid){return false;}
        }
    }
}
