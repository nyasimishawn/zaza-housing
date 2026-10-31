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
        for (String name : new String[]{"source.upload", "processed.mp4", "poster.jpg"}) Files.deleteIfExists(workspace.resolve(name));
        Files.deleteIfExists(workspace);
    }
    public String save(Path processed) throws IOException {
        String key = UUID.randomUUID() + ".mp4";
        try { Files.move(processed, path(key), StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException error) { Files.move(processed, path(key)); }
        return key;
    }
    public void savePoster(String key, Path poster) throws IOException {
        Path target = posterPath(key);
        try { Files.move(poster, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException error) { Files.move(poster, target); }
    }
    public FileSystemResource poster(String key) throws IOException {
        Path target = posterPath(key);
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target)) throw new IOException("Poster unavailable");
        return new FileSystemResource(target);
    }
    private Path posterPath(String key) { path(key); return directory.resolve(key.replace(".mp4", ".poster.jpg")); }
    public com.zaza.housing.model.StagedVideo stage(org.springframework.web.multipart.MultipartFile file, UUID id) throws IOException {
        if (file.isEmpty() || file.getSize()>50L*1024*1024) throw new IOException("Invalid upload size");
        String key="source-"+id+".upload"; Path target=source(key);
        Path temporary=Files.createTempFile(directory,".stage-", ".tmp");
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256"); long total=0;
            try(var input=file.getInputStream();var output=Files.newOutputStream(temporary)) {
                byte[] buffer=new byte[8192];int n;
                while((n=input.read(buffer))!=-1) {
                    total+=n; if(total>50L*1024*1024) throw new IOException("Invalid upload size");
                    output.write(buffer,0,n); digest.update(buffer,0,n);
                }
            }
            if(total==0) throw new IOException("Empty upload");
            try { Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException error) { Files.move(temporary,target); }
            return new com.zaza.housing.model.StagedVideo(key,java.util.HexFormat.of().formatHex(digest.digest()),total);
        } catch(java.security.NoSuchAlgorithmException error) { throw new IOException(error); }
        finally { Files.deleteIfExists(temporary); }
    }
    public Path staged(String key) throws IOException {
        Path target=source(key);
        if(Files.isSymbolicLink(target)||!Files.isRegularFile(target)||Files.size(target)>50L*1024*1024) throw new IOException("Source unavailable");
        return target;
    }
    public void removeSource(String key) throws IOException { Files.deleteIfExists(source(key)); }
    private Path source(String key) {
        if(!key.matches("source-[0-9a-f-]{36}\\.upload")) throw new IllegalArgumentException("Invalid upload key");
        return directory.resolve(key);
    }
    public FileSystemResource read(String key) throws IOException {
        Path path = path(key);
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) throw new IOException("Video content unavailable");
        return new FileSystemResource(path);
    }
    public void removeUncommitted(String key) throws IOException {
        Files.deleteIfExists(path(key)); Files.deleteIfExists(posterPath(key));
    }
    private Path path(String key) {
        if (!key.matches("[0-9a-f-]{36}\\.mp4")) throw new IllegalArgumentException("Invalid video storage key");
        Path path = directory.resolve(key).normalize();
        if (!path.startsWith(directory)) throw new IllegalArgumentException("Video path outside storage root");
        return path;
    }
}
