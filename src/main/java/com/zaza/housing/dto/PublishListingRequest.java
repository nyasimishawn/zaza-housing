package com.zaza.housing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import java.util.List;

public record PublishListingRequest(@NotNull @PositiveOrZero Long version,
            @NotNull @PositiveOrZero Long propertyVersion, @NotNull @PositiveOrZero Long unitVersion,
            @Size(max = 20) List<@NotNull @Valid ImageReferenceRequest> images,
            @Size(max = 5) List<@NotNull @Valid VideoReferenceRequest> videos) {
    public PublishListingRequest { images = images == null ? List.of() : List.copyOf(images);
        videos = videos == null ? List.of() : List.copyOf(videos); }
}
