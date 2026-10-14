package com.zaza.housing.filter;

import com.zaza.housing.repository.AuthRepository;
import com.zaza.housing.security.SecurityErrors;
import com.zaza.housing.util.TokenUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class SessionAuthenticationFilter extends OncePerRequestFilter {
    private final AuthRepository users;
    private final Clock clock;

    public SessionAuthenticationFilter(AuthRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null) {
            if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                SecurityErrors.write(response, 401);
                return;
            }
            String token = authorization.substring(7);
            if (!token.matches("[A-Za-z0-9_-]{43}")) {
                SecurityErrors.write(response, 401);
                return;
            }
            var user = users.authenticate(TokenUtils.hashToken(token), clock.instant());
            if (user.isEmpty()) {
                SecurityErrors.write(response, 401);
                return;
            }
            var authentication = UsernamePasswordAuthenticationToken.authenticated(user.get(), null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.get().platformRole())));
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }
}
