package com.zaza.housing.dto;

import com.zaza.housing.model.UserAccount;
import java.time.Instant;

public record LoginResponse(String accessToken, String tokenType, Instant expiresAt, UserAccount user) { }
