package com.zaza.housing.dto;

import jakarta.validation.constraints.PositiveOrZero;

public record VideoJobActionRequest(@PositiveOrZero long version) {}
