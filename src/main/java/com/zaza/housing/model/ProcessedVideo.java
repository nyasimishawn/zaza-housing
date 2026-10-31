package com.zaza.housing.model;
import java.nio.file.Path;
public record ProcessedVideo(Path path, int width, int height, double durationSeconds, long byteSize,
    String contentHash, Path posterPath, String posterHash, long posterSize) {
  public ProcessedVideo(Path path, int width, int height, double durationSeconds, long byteSize) {
    this(path, width, height, durationSeconds, byteSize, null, null, null, 0);
  }
}
