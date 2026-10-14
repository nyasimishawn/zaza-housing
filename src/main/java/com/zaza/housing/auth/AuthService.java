package com.zaza.housing.auth;

import com.zaza.housing.audit.AuditService;
import com.zaza.housing.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final AuthRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final Clock clock;
    private final Duration sessionDuration;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;

    public AuthService(AuthRepository users, PasswordEncoder encoder, AuditService audit, Clock clock,
            @Value("${zaza.auth.session-duration}") Duration sessionDuration) {
        if (sessionDuration.isNegative() || sessionDuration.isZero() || sessionDuration.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Session duration must be positive and no longer than one day.");
        }
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
        this.clock = clock;
        this.sessionDuration = sessionDuration;
        this.dummyHash = encoder.encode("unused-" + java.util.UUID.randomUUID());
    }

    @Transactional
    public UserAccount register(String email, String password, String displayName) {
        validatePassword(password);
        UserAccount user = users.create(normalizeEmail(email), displayName.trim(), encoder.encode(password));
        audit.record(user.id(), null, "USER_REGISTERED", user.id());
        return user;
    }

    @Transactional
    public LoginResult login(String email, String password) {
        validatePassword(password);
        var candidate = users.credentials(normalizeEmail(email));
        boolean matches = encoder.matches(password, candidate.map(AuthRepository.Credentials::passwordHash).orElse(dummyHash));
        if (candidate.isEmpty() || !matches || !candidate.get().active()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Email or password is incorrect.");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        Instant expires = now.plus(sessionDuration);
        UserAccount user = candidate.get().user();
        users.saveSession(hashToken(token), user.id(), now, expires);
        audit.record(user.id(), null, "USER_LOGGED_IN", user.id());
        return new LoginResult(token, "Bearer", expires, user);
    }

    @Transactional
    public void logout(AuthenticatedUser user, String token) {
        users.revokeSession(hashToken(token), user.id());
        audit.record(user.id(), null, "USER_LOGGED_OUT", user.id());
    }

    public UserAccount me(AuthenticatedUser user) {
        return users.findById(user.id()).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Account is unavailable."));
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password must have at least 12 characters and at most 72 UTF-8 bytes.");
        }
    }

    public static String normalizeEmail(String email) { return email.trim().toLowerCase(Locale.ROOT); }

    public static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    public record LoginResult(String accessToken, String tokenType, Instant expiresAt, UserAccount user) { }
}
