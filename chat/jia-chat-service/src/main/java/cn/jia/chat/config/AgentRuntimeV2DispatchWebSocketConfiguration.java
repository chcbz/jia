package cn.jia.chat.config;

import cn.jia.agent.service.AgentRuntimeV2SocketGateway;
import cn.jia.chat.handler.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.time.Clock;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="agent.runtime-v2-dispatch",name="enabled",havingValue="true")
public class AgentRuntimeV2DispatchWebSocketConfiguration {
    @Bean(name="agentRuntimeV2SocketGateway",defaultCandidate=false)
    AgentRuntimeV2SocketGateway gateway(AgentRuntimeV2WebSocketAdapter adapter){return new AgentRuntimeV2SocketGatewayImpl(adapter,Clock.systemUTC());}
}
