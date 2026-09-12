package cn.jia.chat.ai;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** Default chat model that guarantees no provider is selected or invoked. */
public final class DisabledChatModel implements ChatModel {
    @Override
    public ChatResponse call(Prompt prompt) {
        throw new AiProviderCallException(AiFailureCategory.DISABLED);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.error(new AiProviderCallException(AiFailureCategory.DISABLED));
    }
}
