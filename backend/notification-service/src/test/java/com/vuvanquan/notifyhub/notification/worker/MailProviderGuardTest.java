package com.vuvanquan.notifyhub.notification.worker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSender;

class MailProviderGuardTest {
    @Test void real_smtp_cannot_downgrade_authentication_or_transport_security(){
        assertThatThrownBy(()->new MailProviderGuard("smtp.example.com","user","password","sender@example.com",true,false,true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new MailProviderGuard("smtp.example.com","user","password","sender@example.com",false,true,true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new MailProviderGuard("smtp.example.com","user","password","sender@example.com",true,true,false)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(()->new MailProviderGuard("smtp.example.com","user","password","sender@example.com",true,true,true)).doesNotThrowAnyException();
    }
    @Test void smtp_authentication_failure_is_terminal_and_never_persists_raw_server_text(){
        var mail=mock(JavaMailSender.class);var message=new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new Properties()));
        when(mail.createMimeMessage()).thenReturn(message);doThrow(new MailAuthenticationException("secret credential rejected")).when(mail).send(message);
        var task=TwilioSmsProviderTest.task("Hello");
        var email=new SendNotificationTask(task.notificationId(),1,task.tenantId(),task.campaignId(),task.recipientId(),task.correlationId(),"EMAIL","recipient@example.test","Test","Hello",task.createdAt());
        var failure=catchThrowableOfType(()->new EmailDeliveryProvider(mail,"sender@example.test").send(email),DeliveryFailure.class);
        assertThat(failure.retryable()).isFalse();assertThat(failure.getMessage()).isEqualTo("SmtpAuthenticationRejected");
    }
}
