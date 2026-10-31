package com.zaza.housing.dto;

import jakarta.validation.constraints.*;

public record InvitationCommand(@Min(0) long version) {}
