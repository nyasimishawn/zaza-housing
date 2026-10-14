package com.zaza.housing.model;

import java.util.UUID;

public record AuthenticatedUser(UUID id, String email, String platformRole) { }
