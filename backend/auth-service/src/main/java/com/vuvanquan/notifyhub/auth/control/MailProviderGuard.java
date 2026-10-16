package com.vuvanquan.notifyhub.auth.control;

import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component @Profile("providers")
public class MailProviderGuard {
    public MailProviderGuard(@Value("${spring.mail.host}")String host,@Value("${spring.mail.username}")String username,
            @Value("${spring.mail.password}")String password,@Value("${auth.control.mail-from}")String from,
            @Value("${spring.mail.properties.mail.smtp.auth}")boolean authenticated,
            @Value("${spring.mail.properties.mail.smtp.starttls.required}")boolean tls,
            @Value("${spring.mail.properties.mail.smtp.ssl.checkserveridentity}")boolean verifyHost) {
        if(host==null || host.isBlank() || host.endsWith(".invalid") || host.contains("${") || username==null || username.isBlank()
                || password==null || password.isBlank() || password.contains("${") || !authenticated || !tls || !verifyHost)
            throw new IllegalArgumentException("Real SMTP requires configured host/credentials, authentication, mandatory STARTTLS and hostname verification");
        try{var address=new InternetAddress(from,true);address.validate();if(from.contains("\r")||from.contains("\n")||from.endsWith(".invalid"))throw new IllegalArgumentException();}
        catch(Exception invalid){throw new IllegalArgumentException("A valid SMTP sender address is required");}
    }
}
