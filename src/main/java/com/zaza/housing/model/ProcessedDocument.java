package com.zaza.housing.model;

public record ProcessedDocument(ProcessedImage content, int pageCount, String requestHash) {}
