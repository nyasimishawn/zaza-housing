package com.zaza.housing.model;
import java.nio.file.Path;
public record ProcessedVideo(Path path, int width, int height, double durationSeconds, long byteSize) { }
