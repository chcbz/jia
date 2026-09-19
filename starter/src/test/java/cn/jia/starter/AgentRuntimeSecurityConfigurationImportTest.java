package cn.jia.starter;

import cn.jia.JiaApplication;
import cn.jia.agent.config.AgentRuntimeSecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the native Agent Runtime lane against component-scan boundary regressions. */
class AgentRuntimeSecurityConfigurationImportTest {
    @Test
    void applicationExplicitlyImportsNativeRuntimeSecurityConfiguration() {
        Import configurationImport = JiaApplication.class.getAnnotation(Import.class);

        assertTrue(configurationImport != null
                        && Arrays.asList(configurationImport.value())
                        .contains(AgentRuntimeSecurityConfiguration.class),
                "JiaApplication must explicitly import the native Agent Runtime security chain");
    }
}
