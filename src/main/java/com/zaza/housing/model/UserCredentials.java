package com.zaza.housing.model;

public record UserCredentials(UserAccount user, String passwordHash, boolean active) { }
