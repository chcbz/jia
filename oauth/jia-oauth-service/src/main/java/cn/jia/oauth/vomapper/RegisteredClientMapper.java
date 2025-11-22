package cn.jia.oauth.vomapper;

import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
import cn.jia.oauth.dto.ClientSettingDTO;
import cn.jia.oauth.dto.TokenSettingDTO;
import cn.jia.oauth.entity.OauthClientEntity;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class RegisteredClientMapper {
    public static RegisteredClientMapper INSTANCE = new RegisteredClientMapper();

    public RegisteredClient toVO(OauthClientEntity entity) {
        Set<String> clientAuthenticationMethods = MapStruct.toStringSet(entity.getClientAuthenticationMethods());
        Set<String> authorizationGrantTypes = MapStruct.toStringSet(entity.getAuthorizationGrantTypes());
        Set<String> redirectUris = MapStruct.toStringSet(entity.getRedirectUris());
        Set<String> postLogoutRedirectUris = MapStruct.toStringSet(entity.getPostLogoutRedirectUris());
        Set<String> clientScopes = MapStruct.toStringSet(entity.getScopes());

        // @formatter:off
        RegisteredClient.Builder builder = RegisteredClient.withId(entity.getId())
                .clientId(entity.getClientId())
                .clientIdIssuedAt(MapStruct.dateToInstant(entity.getClientIdIssuedAt()))
                .clientSecret(entity.getClientSecret())
                .clientSecretExpiresAt(MapStruct.dateToInstant(entity.getClientSecretExpiresAt()))
                .clientName(entity.getClientName())
                .clientAuthenticationMethods((authenticationMethods) ->
                        clientAuthenticationMethods.forEach(authenticationMethod ->
                                authenticationMethods.add(MapStruct.toClientAuthenticationMethod(authenticationMethod))))
                .authorizationGrantTypes((grantTypes) ->
                        authorizationGrantTypes.forEach(grantType ->
                                grantTypes.add(MapStruct.toAuthorizationGrantType(grantType))))
                .redirectUris((uris) -> uris.addAll(redirectUris))
                .postLogoutRedirectUris((uris) -> uris.addAll(postLogoutRedirectUris))
                .scopes((scopes) -> scopes.addAll(clientScopes));
        // @formatter:on
        ClientSettingDTO clientSettingDTO = JsonUtil.fromJson(entity.getClientSettings(), ClientSettingDTO.class);
        if (clientSettingDTO != null) {
            builder.clientSettings(ClientSettings.withSettings(clientSettingDTO.toMap()).build());
        }
        TokenSettingDTO tokenSettingDTO = JsonUtil.fromJson(entity.getTokenSettings(), TokenSettingDTO.class);
        if (tokenSettingDTO != null) {
            TokenSettings.Builder tokenSettingsBuilder = TokenSettings.withSettings(tokenSettingDTO.toMap());
            if (tokenSettingDTO.getAccessTokenFormat() == null) {
                tokenSettingsBuilder.accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED);
            }
            builder.tokenSettings(tokenSettingsBuilder.build());
        }

        return builder.build();
    }

    public List<RegisteredClient> toList(List<OauthClientEntity> list) {
        return list.stream().map(this::toVO).toList();
    }

    public OauthClientEntity toEntity(RegisteredClient registeredClient) {
        OauthClientEntity oauthClientEntity = new OauthClientEntity();
        oauthClientEntity.setId(registeredClient.getId())
                .setClientId(registeredClient.getClientId())
                .setClientName(registeredClient.getClientName())
                .setClientSecret(registeredClient.getClientSecret())
                .setAuthorizationGrantTypes(MapStruct.fromAuthorizationGrantTypeSet(registeredClient.getAuthorizationGrantTypes()))
                .setClientAuthenticationMethods(MapStruct.fromClientAuthenticationMethodSet(registeredClient.getClientAuthenticationMethods()))
                .setClientIdIssuedAt(MapStruct.instantToDate(registeredClient.getClientIdIssuedAt()))
                .setClientSecretExpiresAt(MapStruct.instantToDate(registeredClient.getClientSecretExpiresAt()))
                .setClientSettings(MapStruct.fromClientSettings(registeredClient.getClientSettings()))
                .setPostLogoutRedirectUris(MapStruct.fromStringSet(registeredClient.getPostLogoutRedirectUris()))
                .setRedirectUris(MapStruct.fromStringSet(registeredClient.getRedirectUris()))
                .setScopes(MapStruct.fromStringSet(registeredClient.getScopes()))
                .setTokenSettings(MapStruct.fromTokenSettings(registeredClient.getTokenSettings()));
        return oauthClientEntity;
    }

    static class MapStruct {
        public static Instant dateToInstant(Date date) {
            return date != null ? date.toInstant() : null;
        }

        public static ClientAuthenticationMethod toClientAuthenticationMethod(String clientAuthenticationMethod) {
            if (ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue().equals(clientAuthenticationMethod)) {
                return ClientAuthenticationMethod.CLIENT_SECRET_BASIC;
            } else if (ClientAuthenticationMethod.CLIENT_SECRET_POST.getValue().equals(clientAuthenticationMethod)) {
                return ClientAuthenticationMethod.CLIENT_SECRET_POST;
            } else if (ClientAuthenticationMethod.NONE.getValue().equals(clientAuthenticationMethod)) {
                return ClientAuthenticationMethod.NONE;
            }
            return new ClientAuthenticationMethod(clientAuthenticationMethod);
        }

        public static AuthorizationGrantType toAuthorizationGrantType(String authorizationGrantType) {
            if (AuthorizationGrantType.AUTHORIZATION_CODE.getValue().equals(authorizationGrantType)) {
                return AuthorizationGrantType.AUTHORIZATION_CODE;
            } else if (AuthorizationGrantType.CLIENT_CREDENTIALS.getValue().equals(authorizationGrantType)) {
                return AuthorizationGrantType.CLIENT_CREDENTIALS;
            } else if (AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(authorizationGrantType)) {
                return AuthorizationGrantType.REFRESH_TOKEN;
            }
            return new AuthorizationGrantType(authorizationGrantType);
        }

        public static Set<String> toStringSet(String str) {
            return str == null ? null : StringUtil.commaDelimitedListToSet(str);
        }

        public static Date instantToDate(Instant instant) {
            return instant != null ? Date.from(instant) : null;
        }

        public static String fromClientAuthenticationMethodSet(Set<ClientAuthenticationMethod> method) {
            return method == null ? null : StringUtil.collectionToCommaDelimitedString(
                    method.stream().map(ClientAuthenticationMethod::getValue).collect(Collectors.toSet()));
        }


        public static String fromAuthorizationGrantTypeSet(Set<AuthorizationGrantType> set) {
            return set == null ? null : StringUtil.collectionToCommaDelimitedString(
                    set.stream().map(AuthorizationGrantType::getValue).collect(Collectors.toSet()));
        }

        public static String fromStringSet(Set<String> set) {
            return set == null ? null : StringUtil.collectionToCommaDelimitedString(set);
        }

        public static String fromClientSettings(ClientSettings clientSettings) {
            return clientSettings == null ? null : JsonUtil.toJson(clientSettings.getSettings());
        }

        public static String fromTokenSettings(TokenSettings tokenSettings) {
            return tokenSettings == null ? null : JsonUtil.toJson(tokenSettings.getSettings());
        }
    }
}
