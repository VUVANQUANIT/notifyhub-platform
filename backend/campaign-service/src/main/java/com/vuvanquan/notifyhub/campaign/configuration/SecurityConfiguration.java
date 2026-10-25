package com.vuvanquan.notifyhub.campaign.configuration;

import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean
    @Profile("local")
    SecurityFilterChain localSecurity(HttpSecurity http) throws Exception {
        // Development actor headers are accepted only on the loopback-bound local server.
        return http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/campaigns", "/api/campaigns/**"))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/campaigns", "/api/campaigns/**").permitAll()
                        .anyRequest().denyAll()).build();
    }

    @Bean
    @Profile("!local")
    SecurityFilterChain jwtSecurity(HttpSecurity http) throws Exception {
        return http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/campaigns", "/api/campaigns/**")
                            .hasAuthority("SCOPE_campaigns:read")
                        .requestMatchers(HttpMethod.POST, "/api/campaigns", "/api/campaigns/**")
                            .hasAuthority("SCOPE_campaigns:write")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults())).build();
    }
}
