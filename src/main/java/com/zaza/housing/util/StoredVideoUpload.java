package com.zaza.housing.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.web.multipart.MultipartFile;

/** A worker's already bounded, private staged input; never accepts caller-supplied paths. */
public final class StoredVideoUpload implements MultipartFile {
  private final Path path;
  public StoredVideoUpload(Path path) { this.path=path; }
  public String getName() { return "file"; }
  public String getOriginalFilename() { return "property-video.mp4"; }
  public String getContentType() { return "application/octet-stream"; }
  public boolean isEmpty() { return getSize()==0; }
  public long getSize() { try { return Files.size(path); } catch(IOException error) { return 0; } }
  public byte[] getBytes() throws IOException { return Files.readAllBytes(path); }
  public InputStream getInputStream() throws IOException { return Files.newInputStream(path); }
  public void transferTo(java.io.File destination) throws IOException { Files.copy(path,destination.toPath()); }
}
