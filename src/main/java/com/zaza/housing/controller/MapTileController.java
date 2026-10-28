package com.zaza.housing.controller;

import com.zaza.housing.service.MapTileService;
import java.time.Duration;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/discovery/tiles")
public class MapTileController {
  private final MapTileService tiles;

  public MapTileController(MapTileService tiles) {
    this.tiles = tiles;
  }

  @GetMapping("/{revision}/{z}/{x}/{y}.png")
  public ResponseEntity<byte[]> tile(
      @PathVariable String revision,
      @PathVariable int z,
      @PathVariable int x,
      @PathVariable int y,
      @RequestHeader(value = "Referer", required = false) String referrer) {
    var bytes = tiles.tile(revision, z, x, y, referrer);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
        .header("X-Content-Type-Options", "nosniff")
        .contentType(MediaType.IMAGE_PNG)
        .contentLength(bytes.length)
        .body(bytes);
  }
}
