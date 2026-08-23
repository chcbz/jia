package cn.jia.oauth.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OAuthResourceIdentityDTO(
        String subject,
        String clientId,
        String username,
        String jiacn,
        List<String> scopes) {
}
