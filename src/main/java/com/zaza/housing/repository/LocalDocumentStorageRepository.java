package com.zaza.housing.repository;

import com.zaza.housing.model.EncryptedDocument;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

@Repository
public class LocalDocumentStorageRepository {
  private final Path directory;

  public LocalDocumentStorageRepository(
      @Value("${zaza.documents.directory:.local-documents}") String directory) throws IOException {
    this.directory = Files.createDirectories(Path.of(directory).toAbsolutePath().normalize());
  }

  public String save(EncryptedDocument encrypted) throws IOException {
    String key = UUID.randomUUID() + ".enc";
    Path temporary = Files.createTempFile(directory, "encrypted-", ".tmp");
    try {
      Files.write(temporary, encrypted.bytes());
      try {
        Files.move(temporary, path(key), StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, path(key));
      }
      return key;
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  public byte[] read(String key, long expectedSize) throws IOException {
    Path path = path(key);
    if (Files.isSymbolicLink(path)
        || !Files.isRegularFile(path)
        || expectedSize > 20971620
        || Files.size(path) != expectedSize) throw new IOException("Document content unavailable");
    return Files.readAllBytes(path);
  }

  public void remove(String key) throws IOException {
    Files.deleteIfExists(path(key));
  }

  private Path path(String key) {
    if (!key.matches("[0-9a-f-]{36}\\.enc"))
      throw new IllegalArgumentException("Invalid encrypted storage key");
    Path path = directory.resolve(key).normalize();
    if (!path.startsWith(directory))
      throw new IllegalArgumentException("Invalid encrypted storage path");
    return path;
  }
}
