package com.zaza.housing.repository;

import com.zaza.housing.model.ProcessedImage;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Repository;

@Repository
public class LocalImageStorageRepository {
    private final Path directory;

    public LocalImageStorageRepository(@Value("${zaza.images.directory}") String directory) throws IOException {
        // The configured root is trusted. Keep its normalized path so cloud filesystem
        // providers need not resolve reparse-point targets with broader access rights.
        this.directory = Files.createDirectories(Path.of(directory).toAbsolutePath().normalize());
    }

    public String save(ProcessedImage image) throws IOException {
        String key = UUID.randomUUID() + "." + image.extension();
        Path temporary = Files.createTempFile(directory, "image-", ".tmp");
        try {
            Files.write(temporary, image.bytes());
            try { Files.move(temporary, path(key), StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, path(key)); }
            return key;
        } finally { Files.deleteIfExists(temporary); }
    }

    public FileSystemResource read(String key) throws IOException {
        Path path = path(key);
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) throw new IOException("Image content unavailable");
        return new FileSystemResource(path);
    }

    public void removeUncommitted(String key) throws IOException { Files.deleteIfExists(path(key)); }

    private Path path(String key) {
        if (!key.matches("[0-9a-f-]{36}\\.(png|jpg)")) throw new IllegalArgumentException("Invalid image storage key");
        Path path = directory.resolve(key).normalize();
        if (!path.startsWith(directory)) throw new IllegalArgumentException("Image path outside storage root");
        return path;
    }
}
