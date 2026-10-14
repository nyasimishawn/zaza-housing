package com.zaza.housing.repository;

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
public class LocalVideoStorageRepository {
    private final Path directory;
    public LocalVideoStorageRepository(@Value("${zaza.videos.directory}") String directory) throws IOException {
        this.directory = Files.createDirectories(Path.of(directory).toAbsolutePath().normalize());
    }
    public Path createWorkspace() throws IOException { return Files.createTempDirectory(directory, ".processing-"); }
    public void removeWorkspace(Path workspace) throws IOException {
        if (!workspace.getParent().equals(directory) || !workspace.getFileName().toString().startsWith(".processing-")) {
            throw new IllegalArgumentException("Invalid video workspace");
        }
        // Only files created by this upload are removed; no recursive traversal.
        for (String name : new String[]{"source.upload", "processed.mp4"}) Files.deleteIfExists(workspace.resolve(name));
        Files.deleteIfExists(workspace);
    }
    public String save(Path processed) throws IOException {
        String key = UUID.randomUUID() + ".mp4";
        try { Files.move(processed, path(key), StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException error) { Files.move(processed, path(key)); }
        return key;
    }
    public FileSystemResource read(String key) throws IOException {
        Path path = path(key);
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) throw new IOException("Video content unavailable");
        return new FileSystemResource(path);
    }
    public void removeUncommitted(String key) throws IOException { Files.deleteIfExists(path(key)); }
    private Path path(String key) {
        if (!key.matches("[0-9a-f-]{36}\\.mp4")) throw new IllegalArgumentException("Invalid video storage key");
        Path path = directory.resolve(key).normalize();
        if (!path.startsWith(directory)) throw new IllegalArgumentException("Video path outside storage root");
        return path;
    }
}
