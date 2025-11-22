package cn.jia.oauth.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@ToString
public class ClientSettingDTO {
    @JsonProperty(value = "settings.client.require-proof-key", defaultValue = "false")
    private Boolean requireProofKey;

    @JsonProperty(value = "settings.client.require-authorization-consent", defaultValue = "false")
    private Boolean requireAuthorizationConsent;

    @JsonIgnore
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("settings.client.require-proof-key", this.getRequireProofKey());
        map.put("settings.client.require-authorization-consent", this.getRequireAuthorizationConsent());
        return map;
    }
}
