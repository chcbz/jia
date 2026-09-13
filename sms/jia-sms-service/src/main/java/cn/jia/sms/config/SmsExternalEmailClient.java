package cn.jia.sms.config;

import org.springframework.stereotype.Component;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Properties;

/** Single-shot SMTP boundary using request-local properties and the remaining SMS budget. */
@Component
public class SmsExternalEmailClient {
    private final SmsExternalHttpClient externalHttpClient;

    public SmsExternalEmailClient(SmsExternalHttpClient externalHttpClient) {
        this.externalHttpClient = externalHttpClient;
    }

    public boolean send(SmsExternalHttpClient.OperationBudget budget, String title, String content,
            String from, String to, String username, String password, String smtpHost) {
        SmsExternalHttpClient.CallTimeouts callTimeouts = externalHttpClient.prepareSdkCall(budget);
        long startedNanos = budget.markCallStarted();
        String outcome = "success";
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.host", smtpHost);
        properties.setProperty("mail.smtp.auth", "true");
        properties.setProperty("mail.smtp.connectiontimeout", Integer.toString(callTimeouts.connectTimeoutMillis()));
        properties.setProperty("mail.smtp.timeout", Integer.toString(callTimeouts.readTimeoutMillis()));
        properties.setProperty("mail.smtp.writetimeout",
                Integer.toString(callTimeouts.connectionRequestTimeoutMillis()));

        Transport transport = null;
        try {
            Session session = Session.getInstance(properties);
            MimeMessage message = new MimeMessage(session);
            message.setSubject(title);
            message.setContent("<meta http-equiv=Content-Type content=text/html; charset=gb2312>" + content,
                    "text/html;charset=GB2312");
            message.setFrom(new InternetAddress(from));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.saveChanges();

            transport = session.getTransport("smtp");
            transport.connect(smtpHost, username, password);
            transport.sendMessage(message, message.getRecipients(Message.RecipientType.TO));
            return true;
        } catch (MessagingException exception) {
            outcome = "failure";
            throw new SmsExternalHttpClient.SmsExternalCallRejectedException("SMS email dependency failed");
        } catch (RuntimeException | Error failure) {
            outcome = "failure";
            throw failure;
        } finally {
            closeQuietly(transport);
            budget.observeIfSlow("smtp", startedNanos, outcome);
        }
    }

    private void closeQuietly(Transport transport) {
        if (transport == null) {
            return;
        }
        try {
            transport.close();
        } catch (MessagingException ignored) {
            // The send result is already known; close failure must not expose provider details.
        }
    }
}
