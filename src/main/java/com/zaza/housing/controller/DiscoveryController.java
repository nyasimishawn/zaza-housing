package com.zaza.housing.controller;

import com.zaza.housing.dto.*;
import com.zaza.housing.service.*;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/discovery")
public class DiscoveryController {
  private final DiscoveryReadService discovery;
  private final MapTileService tiles;
  private final Clock clock;

  public DiscoveryController(DiscoveryReadService discovery, MapTileService tiles, Clock clock) {
    this.discovery = discovery;
    this.tiles = tiles;
    this.clock = clock;
  }

  @GetMapping("/search")
  public DiscoveryPageResponse search(@Valid @ModelAttribute DiscoveryQuery q) {
    return discovery.search(q);
  }

  @GetMapping("/places")
  public List<DiscoveryPlaceResponse> places(
      @RequestParam String q,
      @RequestParam(required = false) String countryCode,
      @RequestParam(defaultValue = "10") int limit) {
    return discovery.places(q, countryCode, limit);
  }

  @GetMapping("/comparison")
  public DiscoveryComparisonResponse compare(@RequestParam List<UUID> ids) {
    var rows = discovery.compare(ids);
    return new DiscoveryComparisonResponse(
        null,
        "Current comparison",
        ids,
        0,
        clock.instant(),
        clock.instant(),
        rows,
        discovery.currency(rows));
  }

  @GetMapping("/map")
  public DiscoveryMapConfiguration map() {
    return tiles.configuration();
  }
}
