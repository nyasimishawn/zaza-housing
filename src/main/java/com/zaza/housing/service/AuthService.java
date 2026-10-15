package com.zaza.housing.service;

import com.zaza.housing.dto.LoginResponse;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.UserAccount;
import com.zaza.housing.model.UserCredentials;
import com.zaza.housing.repository.AuthRepository;
import com.zaza.housing.util.EmailNormalizer;
import com.zaza.housing.util.Pagination;
import com.zaza.housing.util.TokenUtils;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
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
        UserAccount user = users.create(EmailNormalizer.normalize(email), displayName.trim(), encoder.encode(password));
        audit.record(user.id(), null, "USER_REGISTERED", user.id());
        return user;
    }

    @Transactional
    public LoginResponse login(String email, String password) {
        validatePassword(password);
        var candidate = users.credentials(EmailNormalizer.normalize(email));
        boolean matches = encoder.matches(password, candidate.map(UserCredentials::passwordHash).orElse(dummyHash));
        if (candidate.isEmpty() || !matches || !candidate.get().active()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Email or password is incorrect.");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        Instant expires = now.plus(sessionDuration);
        UserAccount user = candidate.get().user();
        users.saveSession(TokenUtils.hashToken(token), user.id(), now, expires);
        audit.record(user.id(), null, "USER_LOGGED_IN", user.id());
        return new LoginResponse(token, "Bearer", expires, user);
    }

    @Transactional
    public void logout(AuthenticatedUser user, String token) {
        users.revokeSession(TokenUtils.hashToken(token), user.id());
        audit.record(user.id(), null, "USER_LOGGED_OUT", user.id());
    }

    public UserAccount me(AuthenticatedUser user) {
        return users.findById(user.id()).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Account is unavailable."));
    }

    public List<UserAccount> listUsers(int limit, int offset) {
        Pagination.validate(limit, offset);
        return users.listUsers(limit, offset);
    }

    public void reauthenticate(AuthenticatedUser actor,String password) {
        validatePassword(password);
        var candidate=users.credentials(actor.email());
        boolean matches=encoder.matches(password,candidate.map(UserCredentials::passwordHash).orElse(dummyHash));
        if(candidate.isEmpty()||!matches||!candidate.get().active()||!candidate.get().user().id().equals(actor.id())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED,"Password confirmation failed.");
        }
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password must have at least 12 characters and at most 72 UTF-8 bytes.");
        }
    }

}
