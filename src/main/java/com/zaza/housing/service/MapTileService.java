package com.zaza.housing.service;

import com.zaza.housing.dto.DiscoveryMapConfiguration;
import com.zaza.housing.exception.ApiException;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class MapTileService {
  private static final long TTL = 7 * 24 * 3600L;
  private final String template, attribution, attributionUrl, revision;
  private final Path directory;
  private final Clock clock;
  private final long maximum;
  private final boolean enabled;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final Semaphore connections = new Semaphore(4);
  private final Object[] locks = new Object[64];
  private final Object quota = new Object();

  public MapTileService(
      Clock clock,
      @Value("${zaza.discovery.map-enabled:true}") boolean enabled,
      @Value("${zaza.discovery.tile-template:https://tile.openstreetmap.org/{z}/{x}/{y}.png}")
          String template,
      @Value("${zaza.discovery.tile-directory:.local-map-tiles}") String directory,
      @Value("${zaza.discovery.tile-cache-mb:200}") long maximum,
      @Value("${zaza.discovery.tile-attribution:© OpenStreetMap contributors}") String attribution,
      @Value("${zaza.discovery.tile-attribution-url:https://www.openstreetmap.org/copyright}")
          String attributionUrl,
      @Value("${zaza.discovery.allow-local-http:false}") boolean local) {
    this.clock = clock;
    this.enabled = enabled;
    this.template = template;
    this.attribution = attribution;
    this.attributionUrl = attributionUrl;
    if (maximum < 1 || maximum > 10000)
      throw new IllegalArgumentException("Map cache size must be 1–10000 MB.");
    this.maximum = maximum * 1024 * 1024;
    var uri = URI.create(template.replace("{z}", "0").replace("{x}", "0").replace("{y}", "0"));
    if (!template.contains("{z}")
        || !template.contains("{x}")
        || !template.contains("{y}")
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getFragment() != null
        || !(uri.getScheme().equals("https")
            || local
                && uri.getScheme().equals("http")
                && Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost())))
      throw new IllegalArgumentException("Configure a fixed HTTPS map tile template.");
    var source = URI.create(attributionUrl);
    if (!"https".equals(source.getScheme()) || source.getHost() == null)
      throw new IllegalArgumentException("Configure a public HTTPS map attribution link.");
    revision = AccountSecurityService.hash(template).substring(0, 16);
    this.directory = Path.of(directory).toAbsolutePath().normalize().resolve(revision);
    Arrays.setAll(locks, i -> new Object());
  }

  public DiscoveryMapConfiguration configuration() {
    return new DiscoveryMapConfiguration(
        enabled ? "/api/v1/discovery/tiles/" + revision + "/{z}/{x}/{y}.png" : null,
        attribution,
        attributionUrl,
        2,
        19,
        "GeoNames CC BY 4.0 — Kenya public place centres");
  }

  public byte[] tile(String revision, int z, int x, int y, String referrer) {
    if (!enabled
        || !this.revision.equals(revision)
        || z < 2
        || z > 19
        || x < 0
        || y < 0
        || x >= (1 << z)
        || y >= (1 << z)) throw new ApiException(HttpStatus.NOT_FOUND, "Map tile not found.");
    Path file = directory.resolve(z + "/" + x + "/" + y + ".png").normalize();
    if (!file.startsWith(directory)) throw unavailable();
    synchronized (locks[Math.floorMod(Objects.hash(z, x, y), locks.length)]) {
      try {
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
            && Files.size(file) <= 1024 * 1024
            && Files.getLastModifiedTime(file)
                .toInstant()
                .plusSeconds(TTL)
                .isAfter(clock.instant())) return Files.readAllBytes(file);
        if (!connections.tryAcquire(10, TimeUnit.SECONDS)) throw unavailable();
        byte[] bytes;
        try {
          var request =
              HttpRequest.newBuilder(
                      URI.create(
                          template
                              .replace("{z}", "" + z)
                              .replace("{x}", "" + x)
                              .replace("{y}", "" + y)))
                  .timeout(Duration.ofSeconds(10))
                  .header("User-Agent", "Zaza/1.0 (+https://github.com/nyasimishawn/zaza-housing)");
          if (referrer != null)
            try {
              var r = URI.create(referrer);
              if (r.getScheme() != null
                  && Set.of("http", "https").contains(r.getScheme())
                  && r.getHost() != null
                  && r.getUserInfo() == null)
                request.header("Referer", r.getScheme() + "://" + r.getRawAuthority() + "/");
            } catch (IllegalArgumentException ignored) {
            }
          var response =
              client.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
          try (var body = response.body()) {
            if (response.statusCode() != 200) throw unavailable();
            bytes = body.readNBytes(1024 * 1024 + 1);
          }
          if (bytes.length < 8
              || bytes.length > 1024 * 1024
              || !Arrays.equals(
                  Arrays.copyOf(bytes, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
            throw unavailable();
        } finally {
          connections.release();
        }
        synchronized (quota) {
          Files.createDirectories(directory);
          long used;
          try (var files = Files.walk(directory)) {
            used =
                files
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .mapToLong(
                        p -> {
                          try {
                            return Files.size(p);
                          } catch (IOException e) {
                            return maximum;
                          }
                        })
                    .sum();
          }
          if (used + bytes.length > maximum) {
            try (var files = Files.walk(directory)) {
              for (Path old :
                  files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList())
                if (Files.getLastModifiedTime(old)
                    .toInstant()
                    .plusSeconds(TTL)
                    .isBefore(clock.instant())) {
                  used -= Files.size(old);
                  Files.deleteIfExists(old);
                }
            }
          }
          if (used + bytes.length > maximum) throw unavailable();
          Files.createDirectories(file.getParent());
          Path temporary = Files.createTempFile(file.getParent(), "tile-", ".tmp");
          try {
            Files.write(temporary, bytes);
            Files.move(
                temporary,
                file,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
            Files.setLastModifiedTime(file, FileTime.from(clock.instant()));
          } finally {
            Files.deleteIfExists(temporary);
          }
        }
        return bytes;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw unavailable();
      } catch (IOException e) {
        throw unavailable();
      }
    }
  }

  private ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "The basemap is temporarily unavailable. Listing and area search remain available.");
  }
}
