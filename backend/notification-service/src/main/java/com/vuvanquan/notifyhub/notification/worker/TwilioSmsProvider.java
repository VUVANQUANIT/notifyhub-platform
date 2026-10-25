package com.vuvanquan.notifyhub.notification.worker;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component @Primary
@ConditionalOnProperty(name="notification.sms.provider",havingValue="twilio")
public class TwilioSmsProvider implements SmsDeliveryProvider {
    private static final int MAX_RESPONSE=65536;
    private final HttpClient http;
    private final URI endpoint;
    private final String authorization;
    private final String senderKey;
    private final String sender;
    private final ObjectMapper json;

    @Autowired
    public TwilioSmsProvider(@Value("${notification.sms.twilio.account-sid}")String account,
            @Value("${notification.sms.twilio.api-key-sid}")String key,
            @Value("${notification.sms.twilio.api-key-secret}")String secret,
            @Value("${notification.sms.twilio.from:}")String from,
            @Value("${notification.sms.twilio.messaging-service-sid:}")String messagingService,ObjectMapper json) {
        this(account,key,secret,from,messagingService,json,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("https://api.twilio.com/2010-04-01/Accounts/"+account+"/Messages.json"));
    }
    // Package-private transport injection is for local protocol tests; runtime endpoint is fixed HTTPS Twilio.
    TwilioSmsProvider(String account,String key,String secret,String from,String messagingService,ObjectMapper json,HttpClient http,URI endpoint) {
        if(account==null || !account.matches("AC[0-9a-fA-F]{32}") || key==null || !key.matches("SK[0-9a-fA-F]{32}")
                || secret==null || secret.length()<20 || secret.length()>256 || secret.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Valid Twilio account/API-key credentials are required");
        boolean hasFrom=from!=null&&!from.isBlank(),hasService=messagingService!=null&&!messagingService.isBlank();
        if(hasFrom==hasService || (hasFrom&&!from.matches("\\+[1-9]\\d{7,14}")) || (hasService&&!messagingService.matches("MG[0-9a-fA-F]{32}")))
            throw new IllegalArgumentException("Configure exactly one Twilio E.164 sender or Messaging Service SID");
        this.senderKey=hasService?"MessagingServiceSid":"From";this.sender=hasService?messagingService:from;
        this.authorization="Basic "+Base64.getEncoder().encodeToString((key+":"+secret).getBytes(StandardCharsets.UTF_8));
        this.json=json;this.http=http;this.endpoint=endpoint;
    }
    @Override public String send(SendNotificationTask task) {
        if(!"SMS".equals(task.channel()) || task.destination()==null || !task.destination().matches("\\+[1-9]\\d{7,14}")
                || task.body()==null || task.body().isBlank() || task.body().codePointCount(0,task.body().length())>1600)
            throw new DeliveryFailure("InvalidSmsMessage",false);
        String form="To="+encode(task.destination())+"&"+senderKey+"="+encode(sender)+"&Body="+encode(task.body());
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).header("Authorization",authorization)
                .header("Content-Type","application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(form,StandardCharsets.UTF_8)).build();
        CompletableFuture<HttpResponse<byte[]>> pending=http.sendAsync(request,info->new LimitedBody());
        HttpResponse<byte[]> response;
        try { response=pending.get(10,TimeUnit.SECONDS); }
        catch(InterruptedException interrupted){pending.cancel(true);Thread.currentThread().interrupt();throw new DeliveryFailure("SmsProviderInterrupted",true);}
        catch(TimeoutException timeout){pending.cancel(true);throw new DeliveryFailure("SmsProviderTimeout",true);}
        catch(ExecutionException failure){
            Throwable cause=failure;while(cause.getCause()!=null)cause=cause.getCause();
            throw new DeliveryFailure(cause instanceof ResponseTooLarge?"SmsProviderResponseTooLarge":"SmsProviderUnavailable",!(cause instanceof ResponseTooLarge));
        }
        int status=response.statusCode();
        if(status==429 || status==408 || status>=500)throw new DeliveryFailure(status==429?"SmsProviderRateLimited":"SmsProviderUnavailable",true,retryAfter(response));
        if(status==401 || status==403)throw new DeliveryFailure("SmsAuthenticationRejected",false);
        if(status!=200 && status!=201)throw new DeliveryFailure("SmsRequestRejected",false);
        try {
            var body=json.readTree(response.body());String sid=body.path("sid").asString(),state=body.path("status").asString();
            if(!sid.matches("SM[0-9a-fA-F]{32}") || !Set.of("queued","accepted","sending","sent","delivered").contains(state))throw new IllegalArgumentException("Invalid response");
            return sid;
        } catch(RuntimeException invalid){
            // A malformed success may already have accepted the SMS: require operator review, not automatic resubmission.
            throw new DeliveryFailure("SmsInvalidProviderResponse",false);
        }
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    @jakarta.annotation.PreDestroy
    public void close(){http.close();}
    private static Duration retryAfter(HttpResponse<?> response){
        try{return Duration.ofSeconds(Math.max(0,Math.min(3600,Long.parseLong(response.headers().firstValue("Retry-After").orElse("0")))));}
        catch(NumberFormatException malformed){return null;}
    }
    private static final class ResponseTooLarge extends IOException {}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;private long size;private boolean stopped;
        public CompletionStage<byte[]> getBody(){return delegate.getBody();}
        public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;delegate.onSubscribe(subscription);}
        public void onNext(List<ByteBuffer> buffers){
            if(stopped)return;
            for(var buffer:buffers)size+=buffer.remaining();
            if(size>MAX_RESPONSE){stopped=true;subscription.cancel();delegate.onError(new ResponseTooLarge());}
            else delegate.onNext(buffers);
        }
        public void onError(Throwable error){if(!stopped){stopped=true;delegate.onError(error);}}
        public void onComplete(){if(!stopped){stopped=true;delegate.onComplete();}}
    }
}
