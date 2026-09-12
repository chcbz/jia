package cn.jia.wx.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WxExternalHttpClientBuilderTest {
    @Test
    void buildsAClientWhenPhasesFitTheTotalBudget() {
        WxExternalHttpClientBuilder builder = new WxExternalHttpClientBuilder(250, 500, 1750, 2500);

        assertDoesNotThrow(() -> builder.build().close());
    }

    @Test
    void rejectsPhaseBudgetsThatExceedTheTotalBudget() {
        assertThrows(IllegalArgumentException.class,
                () -> new WxExternalHttpClientBuilder(250, 500, 1751, 2500));
    }
}
