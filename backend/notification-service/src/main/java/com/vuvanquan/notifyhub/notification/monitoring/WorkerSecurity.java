package com.vuvanquan.notifyhub.notification.monitoring;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;

@Configuration(proxyBeanMethods = false)
public class WorkerSecurity {
    @Bean @Order(1) @ConditionalOnProperty(name="notification.operations.enabled",havingValue="true")
    SecurityFilterChain operationsFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/operations/**").sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.anyRequest().hasAuthority("SCOPE_users:write"))
            .oauth2ResourceServer(o->o.jwt(Customizer.withDefaults())).build();
    }
    // This worker has no interactive users; avoid Boot creating an unused default account.
    @Bean
    UserDetailsService workerUsers() { return new InMemoryUserDetailsManager(); }

    @Bean @Order(2)
    SecurityFilterChain workerFilterChain(HttpSecurity http) throws Exception {
        return http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().denyAll()).build();
    }
}
