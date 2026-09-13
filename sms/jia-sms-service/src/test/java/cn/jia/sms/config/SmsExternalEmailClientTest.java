package cn.jia.sms.config;

import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmsExternalEmailClientTest extends BaseMockTest {
    @Mock SmsExternalHttpClient externalHttpClient;

    @Test
    void rejectsBeforeSmtpWorkWhenTheSharedBoundaryRejectsTheCall() {
        SmsExternalHttpClient.SmsExternalCallRejectedException rejected =
                new SmsExternalHttpClient.SmsExternalCallRejectedException("safe");
        SmsExternalHttpClient.OperationBudget budget = new SmsExternalHttpClient.OperationBudget(
                new SmsExternalHttpTimeouts(), () -> 0L);
        when(externalHttpClient.prepareSdkCall(budget)).thenThrow(rejected);
        SmsExternalEmailClient emailClient = new SmsExternalEmailClient(externalHttpClient);

        assertThrows(SmsExternalHttpClient.SmsExternalCallRejectedException.class,
                () -> emailClient.send(budget, "sensitive-title", "sensitive-body", "from@example.com",
                        "to@example.com", "user", "secret", "smtp.example.com"));

        verify(externalHttpClient).prepareSdkCall(budget);
    }

    @Test
    void smtpDefaultsInheritExistingMailPropertiesWithoutPerfTimeouts() {
        Properties existing = new Properties();
        existing.setProperty("mail.smtp.timeout", "60000");

        Properties properties = SmsExternalEmailClient.smtpProperties(
                "smtp.example.com", existing, new SmsExternalHttpClient.CallTimeouts(null, null, null));

        assertEquals("smtp.example.com", properties.getProperty("mail.smtp.host"));
        assertEquals("60000", properties.getProperty("mail.smtp.timeout"));
        assertNull(properties.getProperty("mail.smtp.connectiontimeout"));
        assertNull(properties.getProperty("mail.smtp.writetimeout"));
    }

    @Test
    void smtpTimeoutOverridesRequireExplicitConfiguration() {
        Properties properties = SmsExternalEmailClient.smtpProperties(
                "smtp.example.com", new Properties(), new SmsExternalHttpClient.CallTimeouts(250, 500, 1750));

        assertEquals("500", properties.getProperty("mail.smtp.connectiontimeout"));
        assertEquals("1750", properties.getProperty("mail.smtp.timeout"));
        assertEquals("250", properties.getProperty("mail.smtp.writetimeout"));
    }
}
