package com.zaza.housing.service;

import com.zaza.housing.exception.ApiException;
import com.zaza.housing.repository.AccountSecurityRepository;
import com.zaza.housing.util.SecurityCryptography;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SecurityMailService {
  private final AccountSecurityRepository repository;
  private final SecurityCryptography crypto;
  private final Clock clock;
  private final String mode, host, user, password, from;
  private final int port;
  private final Path directory;

  public SecurityMailService(
      AccountSecurityRepository repository,
      SecurityCryptography crypto,
      Clock clock,
      @Value("${zaza.security.mail-mode:off}") String mode,
      @Value("${zaza.security.smtp-host:}") String host,
      @Value("${zaza.security.smtp-port:587}") int port,
      @Value("${zaza.security.smtp-user:}") String user,
      @Value("${zaza.security.smtp-password:}") String password,
      @Value("${zaza.security.mail-from:}") String from,
      @Value("${zaza.security.local-mail-directory:.local-security-mail}") String directory) {
    if (!Set.of("off", "local", "smtp").contains(mode))
      throw new IllegalArgumentException("Invalid security mail mode");
    this.repository = repository;
    this.crypto = crypto;
    this.clock = clock;
    this.mode = mode;
    this.host = host;
    this.port = port;
    this.user = user;
    this.password = password;
    this.from = from;
    this.directory = Path.of(directory).toAbsolutePath().normalize();
  }

  public boolean ready() {
    return crypto.configured()
        && (mode.equals("local") || (mode.equals("smtp") && !host.isBlank() && !from.isBlank()));
  }

  public void requireReady() {
    if (!ready())
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Account email delivery is not configured. Contact support.");
  }

  public void queue(UUID user, String purpose, String token, Instant until) {
    requireReady();
    UUID id = UUID.randomUUID();
    String body =
        "Zaza account security\n\n"
            + (purpose.equals("VERIFY_EMAIL") ? "Verify your email" : "Reset your password")
            + "\n"
            + "Open Zaza, choose Account security / account recovery, and enter this one-time"
            + " token:\n\n"
            + token
            + "\n\nExpires at "
            + until
            + " UTC. Never share this token. If you did not request this, ignore the message. Zaza"
            + " support will never ask for your password or authenticator codes.";
    repository.outbox(
        id,
        user,
        purpose,
        crypto.encrypt("MAIL|" + id, body.getBytes(StandardCharsets.UTF_8)),
        until,
        clock.instant());
  }

  @Scheduled(fixedDelayString = "${zaza.security.mail-poll-ms:60000}")
  @Transactional
  public void deliver() {
    if (!ready()) return;
    for (var item : repository.pendingMail()) {
      UUID id = (UUID) item.get("id");
      Instant now = clock.instant();
      int attempts = ((Number) item.get("attempts")).intValue() + 1;
      if (!((java.sql.Timestamp) item.get("expires_at")).toInstant().isAfter(now)) {
        repository.mailAttempt(id, "EXPIRED", attempts, now);
        continue;
      }
      try {
        String body =
            new String(
                crypto.decrypt("MAIL|" + id, (String) item.get("encrypted_message")),
                StandardCharsets.UTF_8);
        if (mode.equals("local")) {
          Files.createDirectories(directory);
          Path target = directory.resolve(id + ".txt");
          try {
            Files.writeString(
                target,
                "To: " + item.get("email") + "\n" + body,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
          } catch (FileAlreadyExistsException ignored) {
            /* Resume after delivery commit loss without replacing the message. */
          }
        } else {
          var sender = new JavaMailSenderImpl();
          sender.setHost(host);
          sender.setPort(port);
          sender.setUsername(user);
          sender.setPassword(password);
          var p = sender.getJavaMailProperties();
          p.setProperty("mail.smtp.auth", String.valueOf(!user.isBlank()));
          p.setProperty("mail.smtp.starttls.enable", "true");
          p.setProperty("mail.smtp.starttls.required", "true");
          p.setProperty("mail.smtp.ssl.checkserveridentity", "true");
          p.setProperty("mail.smtp.connectiontimeout", "5000");
          p.setProperty("mail.smtp.timeout", "5000");
          p.setProperty("mail.smtp.writetimeout", "5000");
          var message = new SimpleMailMessage();
          message.setFrom(from);
          message.setTo((String) item.get("email"));
          message.setSubject("Zaza account security");
          message.setText(body);
          sender.send(message);
        }
        repository.mailAttempt(id, "SENT", attempts, now);
      } catch (Exception error) {
        repository.mailAttempt(id, attempts >= 8 ? "FAILED" : "PENDING", attempts, now);
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("Security mail delivery failed for message {} (attempt {})", id, attempts);
      }
    }
  }
}
