package com.zaza.housing.model;

import java.time.Instant;
import java.util.UUID;

public record UserAccount(UUID id, String email, String displayName, String platformRole, Instant createdAt) { }
