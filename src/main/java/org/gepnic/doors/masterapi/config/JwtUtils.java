package org.gepnic.doors.masterapi.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtils {
    // 1. Fixed string (Must be 32+ characters for HS256)
    private static final String SECRET_STRING = "your-very-secure-and-very-long-secret-key-for-gepnic-doors-2026";
    private static final SecretKey key = Keys.hmacShaKeyFor(SECRET_STRING.getBytes(StandardCharsets.UTF_8));
    
    private static final long EXPIRATION_TIME = 8 * 60 * 60 * 1000; // 8 Hours

    /**
     * UPDATED: Now includes the 'role' claim in the payload
     */
    public String generateToken(String username, String role) {
        return Jwts.builder()
                .setSubject(username)
                .claim("role", role) // This is CRITICAL for the Filter to work
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Extracts the role claim from the JWT body
     */
    public String getRoleFromToken(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            
            return claims.get("role", String.class);
        } catch (Exception e) {
            System.out.println("Role Extraction Error: " + e.getMessage());
            return null;
        }
    }

    public String getUsernameFromToken(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody()
                    .getSubject();
        } catch (Exception e) {
            return null;
        }
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
            return true;
        } catch (Exception e) {
            System.out.println("JWT Validation Error: " + e.getMessage());
            return false;
        }
    }
}