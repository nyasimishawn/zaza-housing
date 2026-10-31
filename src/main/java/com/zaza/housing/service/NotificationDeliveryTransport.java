package com.zaza.housing.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.zaza.housing.model.NotificationDeliveryResult;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * External providers are opt-in. Never log recipients, tokens, credentials or provider response
 * bodies.
 */
@Service
public class NotificationDeliveryTransport {
  private final String emailMode,
      smsMode,
      pushMode,
      host,
      user,
      password,
      from,
      sid,
      auth,
      smsFrom,
      project;
  private final int port;
  private final Path directory;
  private final ObjectMapper json;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private GoogleCredentials credentials;

  public NotificationDeliveryTransport(
      ObjectMapper json,
      @Value("${zaza.notifications.email-mode:off}") String emailMode,
      @Value("${zaza.notifications.sms-mode:off}") String smsMode,
      @Value("${zaza.notifications.push-mode:off}") String pushMode,
      @Value("${zaza.notifications.smtp-host:${zaza.security.smtp-host:}}") String host,
      @Value("${zaza.notifications.smtp-port:${zaza.security.smtp-port:587}}") int port,
      @Value("${zaza.notifications.smtp-user:${zaza.security.smtp-user:}}") String user,
      @Value("${zaza.notifications.smtp-password:${zaza.security.smtp-password:}}") String password,
      @Value("${zaza.notifications.mail-from:${zaza.security.mail-from:}}") String from,
      @Value("${zaza.notifications.twilio-account-sid:}") String sid,
      @Value("${zaza.notifications.twilio-auth-token:}") String auth,
      @Value("${zaza.notifications.sms-from:}") String smsFrom,
      @Value("${zaza.notifications.fcm-project-id:}") String project,
      @Value("${zaza.notifications.local-directory:.local-notification-deliveries}")
          String directory) {
    if (!Set.of("off", "local", "smtp").contains(emailMode)
        || !Set.of("off", "local", "twilio").contains(smsMode)
        || !Set.of("off", "local", "fcm").contains(pushMode))
      throw new IllegalArgumentException("Invalid notification delivery mode");
    if (!sid.isBlank() && !sid.matches("AC[0-9a-fA-F]{32}"))
      throw new IllegalArgumentException("Invalid messaging account configuration");
    if (!project.isBlank() && !project.matches("[a-z][a-z0-9-]{4,62}"))
      throw new IllegalArgumentException("Invalid push project configuration");
    this.json = json;
    this.emailMode = emailMode;
    this.smsMode = smsMode;
    this.pushMode = pushMode;
    this.host = host;
    this.port = port;
    this.user = user;
    this.password = password;
    this.from = from;
    this.sid = sid;
    this.auth = auth;
    this.smsFrom = smsFrom;
    this.project = project;
    this.directory = Path.of(directory).toAbsolutePath().normalize();
  }

  public boolean ready(String channel) {
    return switch (channel) {
      case "EMAIL" ->
          emailMode.equals("local")
              || (emailMode.equals("smtp") && !host.isBlank() && !from.isBlank());
      case "SMS" ->
          smsMode.equals("local")
              || (smsMode.equals("twilio")
                  && !sid.isBlank()
                  && !auth.isBlank()
                  && !smsFrom.isBlank());
      case "PUSH" -> pushMode.equals("local") || (pushMode.equals("fcm") && !project.isBlank());
      default -> false;
    };
  }

  public NotificationDeliveryResult send(
      UUID id, String channel, String recipient, String body, UUID notification) {
    if (!ready(channel)) return NotificationDeliveryResult.unavailable();
    String mode =
        switch (channel) {
          case "EMAIL" -> emailMode;
          case "SMS" -> smsMode;
          default -> pushMode;
        };
    try {
      if (mode.equals("local")) {
        Files.createDirectories(directory);
        Path target = directory.resolve(id + ".txt");
        try {
          Files.writeString(
              target,
              "Channel: " + channel + "\nTo: " + recipient + "\n\n" + body,
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException alreadyWritten) {
          if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            throw new java.io.IOException("Invalid local delivery target");
        }
        return NotificationDeliveryResult.accepted("local:" + id);
      }
      if (channel.equals("EMAIL")) {
        var sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(user);
        sender.setPassword(password);
        var props = sender.getJavaMailProperties();
        props.setProperty("mail.smtp.auth", Boolean.toString(!user.isBlank()));
        props.setProperty("mail.smtp.starttls.enable", "true");
        props.setProperty("mail.smtp.starttls.required", "true");
        props.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        props.setProperty("mail.smtp.connectiontimeout", "10000");
        props.setProperty("mail.smtp.timeout", "15000");
        props.setProperty("mail.smtp.writetimeout", "15000");
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject("Zaza notification");
        message.setText(body);
        sender.send(message);
        return NotificationDeliveryResult.accepted(null);
      }
      if (channel.equals("SMS")) {
        String form =
            "To=" + encode(recipient) + "&From=" + encode(smsFrom) + "&Body=" + encode(body);
        var request =
            HttpRequest.newBuilder(
                    URI.create(
                        "https://api.twilio.com/2010-04-01/Accounts/" + sid + "/Messages.json"))
                .timeout(Duration.ofSeconds(20))
                .header(
                    "Authorization",
                    "Basic "
                        + Base64.getEncoder()
                            .encodeToString((sid + ":" + auth).getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300)
          return NotificationDeliveryResult.accepted(
              json.readTree(response.body()).path("sid").asText());
        return failure(response.statusCode(), false);
      }
      var message = new LinkedHashMap<String, Object>();
      message.put("token", recipient);
      message.put("notification", Map.of("title", "Zaza", "body", body));
      message.put(
          "data",
          notification == null
              ? Map.of("destination", "notifications")
              : Map.of("destination", "notifications", "notificationId", notification.toString()));
      var request =
          HttpRequest.newBuilder(
                  URI.create(
                      "https://fcm.googleapis.com/v1/projects/" + project + "/messages:send"))
              .timeout(Duration.ofSeconds(20))
              .header("Authorization", "Bearer " + accessToken())
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      json.writeValueAsString(Map.of("message", message))))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 200 && response.statusCode() < 300)
        return NotificationDeliveryResult.accepted(
            json.readTree(response.body()).path("name").asText());
      boolean invalid = false;
      if (response.body().length() < 65536)
        for (var detail : json.readTree(response.body()).path("error").path("details"))
          if (detail.path("errorCode").asText().equals("UNREGISTERED")) invalid = true;
      return failure(response.statusCode(), invalid);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new NotificationDeliveryResult("PENDING", "INTERRUPTED", null, false);
    } catch (Exception failure) {
      return new NotificationDeliveryResult("PENDING", "PROVIDER_UNAVAILABLE", null, false);
    }
  }

  private synchronized String accessToken() throws java.io.IOException {
    if (credentials == null)
      credentials =
          GoogleCredentials.getApplicationDefault()
              .createScoped(List.of("https://www.googleapis.com/auth/firebase.messaging"));
    credentials.refreshIfExpired();
    return credentials.getAccessToken().getTokenValue();
  }

  private String encode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  private NotificationDeliveryResult failure(int status, boolean invalid) {
    if (invalid) return new NotificationDeliveryResult("FAILED", "TOKEN_INVALID", null, true);
    if (status == 429 || status >= 500)
      return new NotificationDeliveryResult("PENDING", "PROVIDER_TEMPORARY", null, false);
    return new NotificationDeliveryResult(
        "FAILED",
        status == 401 || status == 403 ? "PROVIDER_AUTH" : "PROVIDER_REJECTED",
        null,
        true);
  }
}
