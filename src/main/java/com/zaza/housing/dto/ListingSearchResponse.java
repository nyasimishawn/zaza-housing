package com.zaza.housing.dto;

import java.util.List;

public record ListingSearchResponse(List<PublicListingResponse> items, int limit, int offset, boolean hasMore) { }
