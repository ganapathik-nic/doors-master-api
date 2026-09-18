package org.gepnic.doors.masterapi.config;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.DirectEncrypter;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import javax.crypto.SecretKey;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;

class JwtExpiryTest {
    @Test void expiredCookieAndBearerStopBeforeDatabaseOrController() throws Exception {
        var utils = new JwtUtils(); utils.init();
        var expired = token(utils, new Date(System.currentTimeMillis() - 60000),
            new Date(System.currentTimeMillis() - 120000), null);
        var users = org.mockito.Mockito.mock(org.gepnic.doors.masterapi.repository.UserRepository.class);
        var events = org.mockito.Mockito.mock(org.springframework.security.authentication.AuthenticationEventPublisher.class);
        for (boolean cookie : new boolean[]{true, false}) {
            var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/v1/external/data-pull/my-requests/10");
            if (cookie) request.setCookies(new jakarta.servlet.http.Cookie("DOORS_SESSION", expired));
            else request.addHeader("Authorization", "Bearer " + expired);
            var response = new org.springframework.mock.web.MockHttpServletResponse();
            var chain = org.mockito.Mockito.mock(jakarta.servlet.FilterChain.class);
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            try {
                new JwtAuthenticationFilter(utils, events, users).doFilter(request, response, chain);
                assertEquals(401, response.getStatus());
                org.mockito.Mockito.verifyNoInteractions(chain, users);
                assertNull(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());
            } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        }
    }

    private String token(JwtUtils utils, Date expiry, Date issued, Date notBefore) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject("test").issueTime(issued)
            .expirationTime(expiry).notBeforeTime(notBefore).build();
        var jwt = new EncryptedJWT(new JWEHeader(JWEAlgorithm.DIR, EncryptionMethod.A256GCM), claims);
        jwt.encrypt(new DirectEncrypter((SecretKey) ReflectionTestUtils.getField(utils, "ephemeralKey")));
        return jwt.serialize();
    }

    @Test void rejectsInvalidTimeClaims() throws Exception {
        var utils = new JwtUtils(); utils.init();
        long now = System.currentTimeMillis();
        Date past = new Date(now - 60000), future = new Date(now + 60000);
        assertNull(utils.parseToken(token(utils, past, past, null)));
        assertNull(utils.parseToken(token(utils, null, past, null)));
        assertNull(utils.parseToken(token(utils, future, null, null)));
        assertNull(utils.parseToken(token(utils, future, future, null)));
        assertNull(utils.parseToken(token(utils, future, past, future)));
        assertNotNull(utils.parseToken(token(utils, future, past, null)));
    }

    @Test void generationAndCookieUseConfiguredLifetime() {
        var utils = new JwtUtils();
        ReflectionTestUtils.setField(utils, "expirationMs", 14400000L);
        utils.init();
        var claims = utils.parseToken(utils.generateToken("test", "EXTERNAL", "test-session"));
        assertNotNull(claims);
        assertEquals(14400000L, ((Date) claims.get("exp")).getTime() - ((Date) claims.get("iat")).getTime());
        assertEquals(14400L, utils.getSessionMaxAgeSeconds());
    }
}
