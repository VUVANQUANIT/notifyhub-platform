package com.vuvanquan.notifyhub.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
@Profile("monitoring")
public class MonitoringSecurity {
    @Bean
    @Order(0)
    SecurityWebFilterChain monitoringFilterChain(ServerHttpSecurity http,
            @Value("${monitoring.username}") String username, @Value("${monitoring.password}") String password) {
        Assert.hasText(username, "Monitoring username is required");
        Assert.hasText(password, "Monitoring password is required");
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        var users = new MapReactiveUserDetailsService(User.withUsername(username)
                .password(encoder.encode(password)).roles("MONITORING").build());
        var manager = new UserDetailsRepositoryReactiveAuthenticationManager(users);
        manager.setPasswordEncoder(encoder);
        return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/actuator/prometheus"))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(org.springframework.security.web.server.savedrequest.NoOpServerRequestCache.getInstance()))
                .authenticationManager(manager).httpBasic(Customizer.withDefaults())
                .authorizeExchange(a -> a.pathMatchers(HttpMethod.GET, "/actuator/prometheus").hasRole("MONITORING")
                        .anyExchange().denyAll()).build();
    }
}
