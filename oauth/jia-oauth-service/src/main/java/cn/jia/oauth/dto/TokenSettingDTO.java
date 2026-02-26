package cn.jia.oauth.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@ToString
public class TokenSettingDTO {
    @JsonProperty("settings.token.access-token-time-to-live")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Duration accessTokenTimeToLive;

    @JsonProperty("settings.token.refresh-token-time-to-live")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Duration refreshTokenTimeToLive;

    @JsonProperty(value = "settings.token.reuse-refresh-tokens", defaultValue = "false")
    private Boolean reuseRefreshTokens;

    @JsonProperty("settings.token.id-token-signature-algorithm")
    @JsonSerialize(using = SignatureAlgorithmSerializer.class)
    @JsonDeserialize(using = SignatureAlgorithmDeserializer.class)
    private SignatureAlgorithm idTokenSignatureAlgorithm;

    @JsonProperty("settings.token.device-code-time-to-live")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Duration deviceCodeTimeToLive;

    @JsonProperty("settings.token.authorization-code-time-to-live")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Duration authorizationCodeTimeToLive;

    @JsonProperty("settings.token.access-token-format")
    @JsonSerialize(using = OAuth2TokenFormatSerializer.class)
    @JsonDeserialize(using = OAuth2TokenFormatDeserializer.class)
    private OAuth2TokenFormat accessTokenFormat;
    
    // SignatureAlgorithm的序列化器
    public static class SignatureAlgorithmSerializer extends ValueSerializer<SignatureAlgorithm> {
        @Override
        public void serialize(SignatureAlgorithm value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
            if (value != null) {
                gen.writeString(value.getName());
            } else {
                gen.writeNull();
            }
        }
    }
    
    // SignatureAlgorithm的反序列化器
    public static class SignatureAlgorithmDeserializer extends ValueDeserializer<SignatureAlgorithm> {
        @Override
        public SignatureAlgorithm deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            String value = p.getValueAsString();
            if (value != null && !value.isEmpty()) {
                return SignatureAlgorithm.from(value);
            }
            return null;
        }
    }
    
    // OAuth2TokenFormat的序列化器
    public static class OAuth2TokenFormatSerializer extends ValueSerializer<OAuth2TokenFormat> {
        @Override
        public void serialize(OAuth2TokenFormat value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
            if (value != null) {
                gen.writeString(value.getValue());
            } else {
                gen.writeNull();
            }
        }
    }
    
    // OAuth2TokenFormat的反序列化器
    public static class OAuth2TokenFormatDeserializer extends ValueDeserializer<OAuth2TokenFormat> {
        @Override
        public OAuth2TokenFormat deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            String value = p.getValueAsString();
            if (value != null && !value.isEmpty()) {
                return new OAuth2TokenFormat(value);
            }
            return null;
        }
    }

    @JsonIgnore
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("settings.token.access-token-time-to-live", this.getAccessTokenTimeToLive());
        map.put("settings.token.refresh-token-time-to-live", this.getRefreshTokenTimeToLive());
        map.put("settings.token.reuse-refresh-tokens", this.getReuseRefreshTokens());
        map.put("settings.token.id-token-signature-algorithm", this.getIdTokenSignatureAlgorithm());
        map.put("settings.token.device-code-time-to-live", this.getDeviceCodeTimeToLive());
        map.put("settings.token.authorization-code-time-to-live", this.getAuthorizationCodeTimeToLive());
        map.put("settings.token.access-token-format", this.getAccessTokenFormat());
        return map;
    }
}