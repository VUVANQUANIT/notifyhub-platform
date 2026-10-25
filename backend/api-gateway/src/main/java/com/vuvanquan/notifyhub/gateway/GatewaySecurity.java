package com.vuvanquan.notifyhub.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.CsrfWebFilter;
import org.springframework.security.web.server.util.matcher.*;

@Configuration
public class GatewaySecurity {
    private static final String[] PUBLIC_AUTH = {"/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout", "/api/auth/password/forgot", "/api/auth/password/reset"};
    @Bean SecurityWebFilterChain security(ServerHttpSecurity http) {
        // Only JSON body credentials and Authorization bearer credentials are supported; no cookie authentication.
        ServerWebExchangeMatcher bearer = exchange -> {
            String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
            return authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                    ? ServerWebExchangeMatcher.MatchResult.match() : ServerWebExchangeMatcher.MatchResult.notMatch();
        };
        var exempt = new OrServerWebExchangeMatcher(ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, PUBLIC_AUTH), bearer);
        return http.securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(org.springframework.security.web.server.savedrequest.NoOpServerRequestCache.getInstance()))
                .csrf(csrf -> csrf.requireCsrfProtectionMatcher(new AndServerWebExchangeMatcher(CsrfWebFilter.DEFAULT_CSRF_MATCHER,
                        new NegatedServerWebExchangeMatcher(exempt))))
                .authorizeExchange(a -> a
                        .pathMatchers(HttpMethod.GET, "/.well-known/jwks.json", "/actuator/health", "/actuator/health/**").permitAll()
                        .pathMatchers(HttpMethod.POST, PUBLIC_AUTH).permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/auth/users").hasAuthority("SCOPE_users:read")
                        .pathMatchers("/api/auth/users/**").hasAuthority("SCOPE_users:write")
                        .pathMatchers("/api/auth/**").authenticated()
                        .pathMatchers(HttpMethod.GET, "/api/campaigns", "/api/campaigns/**").hasAuthority("SCOPE_campaigns:read")
                        .pathMatchers(HttpMethod.POST, "/api/campaigns", "/api/campaigns/**").hasAuthority("SCOPE_campaigns:write")
                        .pathMatchers(HttpMethod.GET, "/api/reports/**").hasAuthority("SCOPE_reports:read")
                        .anyExchange().denyAll())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults())).build();
    }
}
