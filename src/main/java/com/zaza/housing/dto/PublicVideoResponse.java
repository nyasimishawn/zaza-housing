package com.zaza.housing.dto;

import java.util.UUID;

public record PublicVideoResponse(UUID id, String caption, String contentType, int width, int height, double durationSeconds, String url, String posterUrl) {
  public PublicVideoResponse(UUID id, String caption, String contentType, int width, int height, double durationSeconds, String url) {
    this(id, caption, contentType, width, height, durationSeconds, url, null);
  }
}
