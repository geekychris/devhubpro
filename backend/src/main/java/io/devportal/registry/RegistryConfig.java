package io.devportal.registry;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the registry: config properties, the scheduler the health poller runs on, and the
 * optional shared-secret check on writes. DevHub Pro has no auth; when
 * {@code DEVHUB_REGISTRY_TOKEN} is set, POST/PUT/PATCH/DELETE under {@code /api/deployments}
 * need {@code X-DevHub-Token: <token>} so only trusted reporters can change the registry.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(RegistryProperties.class)
public class RegistryConfig implements WebMvcConfigurer {

    public static final String TOKEN_HEADER = "X-DevHub-Token";

    private final RegistryProperties props;

    public RegistryConfig(RegistryProperties props) {
        this.props = props;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TokenInterceptor(props))
            .addPathPatterns("/api/deployments", "/api/deployments/**");
    }

    static final class TokenInterceptor implements HandlerInterceptor {
        private final RegistryProperties props;

        TokenInterceptor(RegistryProperties props) {
            this.props = props;
        }

        @Override
        public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
            if (!props.tokenRequired()) return true;
            String m = req.getMethod();
            if ("GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m)) return true;
            if (matches(req.getHeader(TOKEN_HEADER), props.token())) return true;
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"writes to the "
                + "deployment registry need a valid " + TOKEN_HEADER + " header\",\"timestamp\":\""
                + Instant.now() + "\"}");
            return false;
        }

        static boolean matches(String presented, String expected) {
            if (presented == null || expected == null) return false;
            return MessageDigest.isEqual(presented.trim().getBytes(StandardCharsets.UTF_8),
                expected.trim().getBytes(StandardCharsets.UTF_8));
        }
    }
}
