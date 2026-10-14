package com.zaza.housing.model;

public record ProcessedImage(byte[] bytes, String extension, String contentType, int width, int height) { }
