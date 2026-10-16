package com.vuvanquan.notifyhub.notification.worker;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;

class TwilioSmsProviderTest {
    static final String ACCOUNT="AC"+"a".repeat(32),KEY="SK"+"b".repeat(32),SECRET="test-secret-"+"c".repeat(32),SID="SM"+"d".repeat(32);
    HttpServer server;HttpClient client;TwilioSmsProvider provider;
    final AtomicInteger status=new AtomicInteger(201),requests=new AtomicInteger();
    final AtomicReference<String> response=new AtomicReference<>(),form=new AtomicReference<>(),authorization=new AtomicReference<>();
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        response.set("{\"sid\":\""+SID+"\",\"status\":\"queued\"}");
        server.createContext("/Messages.json",exchange->{
            requests.incrementAndGet();form.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body=response.get().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Retry-After","12");exchange.sendResponseHeaders(status.get(),body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}catch(java.io.IOException cancelled){/* bounded client response cancellation */}
        });server.start();client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        provider=new TwilioSmsProvider(ACCOUNT,KEY,SECRET,"+12025550100","",new ObjectMapper(),client,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/Messages.json"));
    }
    @AfterEach void stop(){server.stop(0);client.close();}
    @Test void uses_api_key_form_encoding_and_returns_only_acceptance_reference(){
        assertThat(provider.send(task("Hello & xin chào + test"))).isEqualTo(SID);
        assertThat(form.get()).contains("To=%2B12025550123","From=%2B12025550100","Body=Hello+%26+xin+ch%C3%A0o+%2B+test");
        assertThat(authorization.get()).isEqualTo("Basic "+Base64.getEncoder().encodeToString((KEY+":"+SECRET).getBytes(StandardCharsets.UTF_8)));
        assertThat(requests.get()).isEqualTo(1);
    }
    @Test void rate_limits_and_server_failures_retry_but_authentication_is_terminal(){
        status.set(429);var limited=catchThrowableOfType(()->provider.send(task("hello")),DeliveryFailure.class);
        assertThat(limited.retryable()).isTrue();assertThat(limited.retryAfter()).isEqualTo(java.time.Duration.ofSeconds(12));assertThat(limited.getMessage()).isEqualTo("SmsProviderRateLimited");
        status.set(503);assertThat(catchThrowableOfType(()->provider.send(task("hello")),DeliveryFailure.class).retryable()).isTrue();
        status.set(401);var rejected=catchThrowableOfType(()->provider.send(task("hello")),DeliveryFailure.class);assertThat(rejected.retryable()).isFalse();assertThat(rejected.getMessage()).isEqualTo("SmsAuthenticationRejected");
        status.set(400);assertThat(catchThrowableOfType(()->provider.send(task("hello")),DeliveryFailure.class).retryable()).isFalse();
    }
    @Test void malformed_success_and_large_response_require_review_and_do_not_leak_provider_text(){
        response.set("{\"error\":\"recipient +12025550123 and secret "+SECRET+"\"}");
        var invalid=catchThrowableOfType(()->provider.send(task("hello")),DeliveryFailure.class);assertThat(invalid.retryable()).isFalse();assertThat(invalid.getMessage()).isEqualTo("SmsInvalidProviderResponse");
        response.set("x".repeat(65537));var large=catchThrowableOfType(()->provider.send(task("hello")),DeliveryFailure.class);
        assertThat(large.retryable()).isFalse();assertThat(large.getMessage()).isEqualTo("SmsProviderResponseTooLarge");
    }
    @Test void refuses_bad_credentials_sender_and_oversize_sms_without_network_call(){
        assertThatThrownBy(()->new TwilioSmsProvider(ACCOUNT,KEY,"", "+12025550100","",new ObjectMapper())).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(SECRET);
        assertThatThrownBy(()->new TwilioSmsProvider(ACCOUNT,KEY,SECRET,"+12025550100","MG"+"a".repeat(32),new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowableOfType(()->provider.send(task("x".repeat(1601))),DeliveryFailure.class).retryable()).isFalse();assertThat(requests.get()).isZero();
    }
    @Test void spring_selects_twilio_without_simulated_fallback_and_rejects_unknown_provider(){
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withUserConfiguration(TwilioSmsProvider.class,SimulatedSmsProvider.class)
            .withBean(ObjectMapper.class,ObjectMapper::new)
            .withPropertyValues("notification.worker.enabled=true","notification.sms.provider=twilio","notification.sms.twilio.account-sid="+ACCOUNT,
                "notification.sms.twilio.api-key-sid="+KEY,"notification.sms.twilio.api-key-secret="+SECRET,"notification.sms.twilio.from=+12025550100")
            .run(context->{assertThat(context).hasNotFailed();assertThat(context.getBean(SmsDeliveryProvider.class)).isInstanceOf(TwilioSmsProvider.class);});
        new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(WorkerConfiguration.class)
            .withInitializer(context->context.getBeanFactory().setConversionService(new org.springframework.boot.convert.ApplicationConversionService()))
            .withPropertyValues("notification.worker.enabled=true","notification.sms.provider=unknown")
            .run(context->{assertThat(context).hasFailed();assertThat(context.getStartupFailure()).hasRootCauseMessage("Unsupported SMS provider");});
    }
    static SendNotificationTask task(String body){return new SendNotificationTask(UUID.randomUUID(),1,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"SMS","+12025550123",null,body,Instant.now());}
}
