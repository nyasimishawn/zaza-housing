package com.zaza.housing.model;

public record EncryptedDocument(byte[] bytes, String keyId) {}
