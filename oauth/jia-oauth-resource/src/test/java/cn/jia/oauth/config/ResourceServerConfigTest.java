package cn.jia.oauth.config;

import cn.jia.test.BaseMockTest;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import cn.jia.user.security.AccountState;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ResourceServerConfigTest extends BaseMockTest {
    @Mock AccountSecurityService accountSecurityService;
    @Mock ObjectProvider<AccountSecurityService> accountSecurityServiceProvider;

    @Test
    void decoderComposesStandardTimeValidationWithAccountValidation() {
        when(accountSecurityServiceProvider.getIfAvailable()).thenReturn(accountSecurityService);
        when(accountSecurityService.findByUserId(17)).thenReturn(Optional.of(
                new AccountSecuritySnapshot(17, "Jia-A", AccountState.ACTIVE, 4)));

        ResourceServerConfig config = new ResourceServerConfig();
        JWKSource<SecurityContext> keys = config.jwkSource();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(keys);
        JwtDecoder decoder = config.jwtDecoder(keys, accountSecurityServiceProvider);

        Jwt valid = decoder.decode(token(encoder, Instant.now().minusSeconds(5), Instant.now().plusSeconds(60), 4));
        assertEquals("17", valid.getClaimAsString("uid"));

        assertThrows(JwtValidationException.class,
                () -> decoder.decode(token(encoder, Instant.now().minusSeconds(120), Instant.now().minusSeconds(60), 4)));
        assertThrows(JwtValidationException.class,
                () -> decoder.decode(token(encoder, Instant.now().minusSeconds(5), Instant.now().plusSeconds(60), 3)));
        verify(accountSecurityService, atLeast(2)).findByUserId(17);
    }

    private static String token(NimbusJwtEncoder encoder, Instant issuedAt, Instant expiresAt, long epoch) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject("alice")
                .claim("client_id", "web")
                .claim("token_kind", "user")
                .claim("uid", "17")
                .claim("jiacn", "Jia-A")
                .claim("username", "alice")
                .claim("auth_epoch", epoch)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
