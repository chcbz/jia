package cn.jia.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.LinkedHashMap;
import java.util.Map;

/** No generated/default server secret. Operators provision versioned HMAC keys separately. */
@ConfigurationProperties("agent.runtime-v2-registration")
public class AgentRuntimeV2RegistrationProperties {
    private boolean activationEnabled;
    private String activeKeyId;
    private Map<String,String> hmacKeys=new LinkedHashMap<>();
    public boolean isActivationEnabled(){return activationEnabled;}
    public void setActivationEnabled(boolean value){activationEnabled=value;}
    public String getActiveKeyId(){return activeKeyId;}
    public void setActiveKeyId(String value){activeKeyId=value;}
    public Map<String,String> getHmacKeys(){return hmacKeys;}
    public void setHmacKeys(Map<String,String> values){hmacKeys=new LinkedHashMap<>(values);}
}
