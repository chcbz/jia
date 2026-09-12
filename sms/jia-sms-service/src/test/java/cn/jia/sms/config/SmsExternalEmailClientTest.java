package cn.jia.sms.config;

import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

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
}
