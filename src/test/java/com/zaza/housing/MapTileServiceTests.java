package com.zaza.housing;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.service.MapTileService;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class MapTileServiceTests {
  @TempDir Path files;
  HttpServer server;
  AtomicInteger requests;
  AtomicReference<String> agent, referer;
  byte[] png;
  int status = 200;

  @BeforeEach
  void start() throws Exception {
    requests = new AtomicInteger();
    agent = new AtomicReference<>();
    referer = new AtomicReference<>();
    png =
        java.util.Base64.getDecoder()
            .decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jXioAAAAASUVORK5CYII=");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        e -> {
          requests.incrementAndGet();
          agent.set(e.getRequestHeaders().getFirst("User-Agent"));
          referer.set(e.getRequestHeaders().getFirst("Referer"));
          e.sendResponseHeaders(status, png.length);
          try (var body = e.getResponseBody()) {
            body.write(png);
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  MapTileService service() {
    return new MapTileService(
        Clock.systemUTC(),
        true,
        "http://127.0.0.1:" + server.getAddress().getPort() + "/{z}/{x}/{y}.png",
        files.toString(),
        1,
        "Map test attribution",
        "https://www.openstreetmap.org/copyright",
        true);
  }

  String revision(MapTileService s) {
    return s.configuration().tileTemplate().split("/")[5];
  }

  @Test
  void requestsIdentifyApplicationPreserveOriginAndCacheRepeatViews() {
    var s = service();
    String revision = revision(s);
    assertThat(s.tile(revision, 2, 1, 1, "https://zaza.example/private/path?token=secret"))
        .isEqualTo(png);
    assertThat(s.tile(revision, 2, 1, 1, null)).isEqualTo(png);
    assertThat(requests.get()).isEqualTo(1);
    assertThat(agent.get()).startsWith("Zaza/");
    assertThat(referer.get()).isEqualTo("https://zaza.example/");
  }

  @Test
  void invalidCoordinatesAndRevisionsNeverCallProvider() {
    var s = service();
    for (int[] xyz : new int[][] {{1, 0, 0}, {20, 1, 1}, {2, 4, 0}, {2, 0, -1}})
      assertThatThrownBy(() -> s.tile(revision(s), xyz[0], xyz[1], xyz[2], null))
          .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> s.tile("../../secret", 2, 1, 1, null))
        .isInstanceOf(ApiException.class);
    assertThat(requests.get()).isZero();
  }

  @Test
  void providerErrorsAndNonImagesAreNotStored() throws Exception {
    var s = service();
    status = 503;
    assertThatThrownBy(() -> s.tile(revision(s), 2, 1, 1, null)).isInstanceOf(ApiException.class);
    status = 200;
    png = "not a PNG image".getBytes();
    assertThatThrownBy(() -> s.tile(revision(s), 2, 1, 1, null)).isInstanceOf(ApiException.class);
    assertThat(Files.walk(files).filter(Files::isRegularFile).count()).isZero();
  }

  @Test
  void malformedReferrersDoNotBreakPublicTileDelivery() {
    var s = service();
    assertThat(s.tile(revision(s), 2, 1, 1, "untrusted-value")).isEqualTo(png);
    assertThat(referer.get()).isNull();
  }

  @Test
  void insecureRemoteTemplatesAreRejected() {
    assertThatThrownBy(
            () ->
                new MapTileService(
                    Clock.systemUTC(),
                    true,
                    "http://example.test/{z}/{x}/{y}.png",
                    files.toString(),
                    1,
                    "Test",
                    "https://example.test",
                    false))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
