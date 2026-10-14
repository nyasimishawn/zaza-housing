package com.zaza.housing.dto;

import java.time.Instant;
import java.util.UUID;

public record ApplicationNoteResponse(UUID id, UUID authorId, String text, Instant createdAt) { }
