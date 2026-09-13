package cn.jia.chat.ai;

import java.util.Arrays;

import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/** Prevents provider chat clients from being initialized while CYF's explicit gate is off. */
public final class AiChatAutoConfigurationImportFilter
        implements AutoConfigurationImportFilter, EnvironmentAware, Ordered {
    static final String OPENAI_CHAT_AUTO_CONFIGURATION =
            "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration";
    static final String DEEPSEEK_CHAT_AUTO_CONFIGURATION =
            "org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata metadata) {
        boolean enabled = environment != null
                && Boolean.parseBoolean(environment.getProperty("jia.chat.ai.enabled", "false"));
        boolean[] matches = new boolean[autoConfigurationClasses.length];
        Arrays.fill(matches, true);
        if (enabled) {
            return matches;
        }
        for (int index = 0; index < autoConfigurationClasses.length; index++) {
            String candidate = autoConfigurationClasses[index];
            if (OPENAI_CHAT_AUTO_CONFIGURATION.equals(candidate)
                    || DEEPSEEK_CHAT_AUTO_CONFIGURATION.equals(candidate)) {
                matches[index] = false;
            }
        }
        return matches;
    }
}
