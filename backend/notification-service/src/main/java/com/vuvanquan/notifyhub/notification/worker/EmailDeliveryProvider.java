package com.vuvanquan.notifyhub.notification.worker;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.*;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

@Component
@ConditionalOnProperty(name = "notification.worker.enabled", havingValue = "true")
public class EmailDeliveryProvider {
    private final JavaMailSender mail;
    private final String from;
    public EmailDeliveryProvider(JavaMailSender mail, @Value("${notification.worker.mail-from}") String from) {
        this.mail = mail;
        this.from = from;
    }

    public String send(SendNotificationTask task) {
        var message = mail.createMimeMessage();
        try {
            var destination = new InternetAddress(task.destination(), true);
            destination.validate();
            var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(destination);
            helper.setSubject(task.subject());
            helper.setText(task.body(), false);
            message.setHeader("X-NotifyHub-Notification-Id", task.notificationId().toString());
            message.setHeader("X-NotifyHub-Correlation-Id", task.correlationId().toString());
            mail.send(message);
            return message.getMessageID();
        } catch (MailException exception) {
            throw new DeliveryFailure("SmtpUnavailable", true);
        } catch (MessagingException exception) {
            throw new DeliveryFailure("InvalidMailMessage", false);
        }
    }
}
