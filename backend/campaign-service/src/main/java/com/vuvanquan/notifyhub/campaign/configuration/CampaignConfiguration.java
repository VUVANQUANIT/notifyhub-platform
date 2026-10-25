package com.vuvanquan.notifyhub.campaign.configuration;

import org.springframework.context.annotation.*;
import java.time.Clock;
import java.time.Duration;

@Configuration
public class CampaignConfiguration {
    @Bean public Clock clock() { return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1000)); }
}
