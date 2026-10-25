package com.vuvanquan.notifyhub.monitoring;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.Assert;

/** A separate scrape identity cannot authorize requests to business APIs. */
@Configuration(proxyBeanMethods = false)
@Profile("monitoring")
public class MonitoringSecurity {
    @Bean
    @Order(0)
    SecurityFilterChain monitoringFilterChain(HttpSecurity http,
            @Value("${monitoring.username}") String username, @Value("${monitoring.password}") String password) throws Exception {
        Assert.hasText(username, "Monitoring username is required");
        Assert.hasText(password, "Monitoring password is required");
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        var users = new InMemoryUserDetailsManager(User.withUsername(username)
                .password(encoder.encode(password)).roles("MONITORING").build());
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return http.securityMatcher("/actuator/prometheus")
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationManager(new ProviderManager(provider)).httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.GET, "/actuator/prometheus").hasRole("MONITORING")
                        .anyRequest().denyAll()).build();
    }
}
