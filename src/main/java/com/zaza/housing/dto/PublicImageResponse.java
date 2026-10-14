package com.zaza.housing.dto;

import java.util.UUID;

public record PublicImageResponse(UUID id, String altText, String contentType, int width, int height, String url) { }
