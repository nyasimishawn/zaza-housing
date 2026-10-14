package com.zaza.housing.auth;

import java.util.UUID;

public record AuthenticatedUser(UUID id, String email, String platformRole) { }
