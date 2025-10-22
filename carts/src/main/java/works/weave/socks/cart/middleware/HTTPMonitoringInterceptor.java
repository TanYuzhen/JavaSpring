package works.weave.socks.cart.middleware;

import io.prometheus.client.Histogram;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.data.rest.core.config.RepositoryRestConfiguration;
import org.springframework.data.rest.core.mapping.ResourceMappings;
import org.springframework.data.rest.webmvc.RepositoryRestHandlerMapping;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashSet;
import java.util.Set;

/**
 * Intercepts every HTTP request, records latency into a Prometheus Histogram,
 * and labels it with service / method / matched-path / status code.
 *
 * <p><b>Note:</b> All JPA-related code has been removed; the interceptor now
 * works in a pure-Mongo Spring Boot 2 application.</p>
 */
@Component
public class HTTPMonitoringInterceptor implements HandlerInterceptor {

    /** Prometheus histogram definition */
    private static final Histogram REQUEST_LATENCY = Histogram.build()
            .name("http_request_duration_seconds")
            .help("Request duration in seconds.")
            .labelNames("service", "method", "path", "status_code")
            .register();

    private static final String START_TIME_KEY = "startTime";

    /* ---------- Spring beans ---------- */
    @Autowired
    private ResourceMappings mappings;

    @Autowired
    private RepositoryRestConfiguration repositoryConfiguration;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    /* ---------- Config / cache ---------- */
    private volatile @Nullable Set<PatternsRequestCondition> urlPatterns;

    @Value("${spring.application.name:carts}")
    private String serviceName;

    /* ---------- Interceptor callbacks ---------- */
    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        request.setAttribute(START_TIME_KEY, System.nanoTime());
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request,
                           HttpServletResponse response,
                           Object handler,
                           @Nullable ModelAndView modelAndView) {

        long start   = (long) request.getAttribute(START_TIME_KEY);
        long elapsed = System.nanoTime() - start;
        double secs  = elapsed / 1_000_000_000.0;

        String matched = matchUrlPattern(request);
        if (!matched.isEmpty()) {
            REQUEST_LATENCY.labels(
                    serviceName,
                    request.getMethod(),
                    matched,
                    Integer.toString(response.getStatus())
            ).observe(secs);
        }
    }

    @Override public void afterCompletion(HttpServletRequest req,
                                          HttpServletResponse res,
                                          Object handler,
                                          @Nullable Exception ex) {}

    /* ---------- Helper methods ---------- */
    private String matchUrlPattern(HttpServletRequest request) {
        for (PatternsRequestCondition pattern : getUrlPatterns()) {
            if (pattern.getMatchingCondition(request) != null &&
                !"/error".equals(request.getServletPath())) {
                return pattern.getMatchingCondition(request)
                              .getPatterns()
                              .iterator()
                              .next();
            }
        }
        return "";
    }

    /**
     * Lazily build & cache all URL patterns handled by the application,
     * including those Spring Data REST auto-exposes.
     */
    private Set<PatternsRequestCondition> getUrlPatterns() {
        if (urlPatterns == null) {
            synchronized (this) {
                if (urlPatterns == null) {
                    Set<PatternsRequestCondition> patterns = new HashSet<>();

                    // MVC controllers
                    requestMappingHandlerMapping.getHandlerMethods()
                            .forEach((mapping, method) ->
                                    patterns.add(mapping.getPatternsCondition()));

                    // Spring Data REST (Mongo repositories)
                    RepositoryRestHandlerMapping repoMapping =
                            new RepositoryRestHandlerMapping(mappings, repositoryConfiguration);
                    repoMapping.setApplicationContext(applicationContext);
                    repoMapping.afterPropertiesSet();
                    repoMapping.getHandlerMethods()
                               .forEach((mapping, method) ->
                                        patterns.add(mapping.getPatternsCondition()));

                    urlPatterns = patterns;
                }
            }
        }
        return urlPatterns;
    }
}
