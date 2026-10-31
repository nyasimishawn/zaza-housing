package com.zaza.housing.service;

import com.zaza.housing.dto.DocumentPageResponse;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.ImageContent;
import com.zaza.housing.model.ProcessedDocument;
import com.zaza.housing.model.ProcessedImage;
import com.zaza.housing.util.DocumentCryptography;
import com.zaza.housing.util.ImageProcessor;
import com.zaza.housing.util.PdfWorker;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentProcessingService {
  private final Semaphore workers = new Semaphore(2);
  private final Semaphore previews = new Semaphore(3);

  public ProcessedDocument process(MultipartFile file) {
    try (var stream = file.getInputStream()) {
      byte[] header = stream.readNBytes(5);
      if (!java.util.Arrays.equals(header, "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
        var image = ImageProcessor.processPrivate(file);
        return new ProcessedDocument(image, 1, DocumentCryptography.hash(image.bytes()));
      }
    } catch (IOException failure) { throw invalid(); }
    if (file.getSize() > PdfWorker.MAX_INPUT)
      throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PDFs must be at most 10 MB.");
    if (!workers.tryAcquire()) throw busy();
    Process process = null;
    try (var input = file.getInputStream()) {
      byte[] bytes = input.readNBytes(PdfWorker.MAX_INPUT + 1);
      if (bytes.length == 0 || bytes.length > PdfWorker.MAX_INPUT) throw invalid();
      var command = command();
      process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      final Process child = process;
      var output = new ByteArrayOutputStream();
      var failed = new AtomicBoolean();
      Thread writer = Thread.ofVirtual().start(() -> {
        try (var destination = child.getOutputStream()) { destination.write(bytes); }
        catch (IOException error) { failed.set(true); }
      });
      Thread reader = Thread.ofVirtual().start(() -> {
        try (var source = child.getInputStream()) {
          byte[] buffer = new byte[8192]; int count;
          while ((count = source.read(buffer)) != -1) {
            if ((long) output.size() + count > PdfWorker.MAX_OUTPUT + 20) {
              failed.set(true); child.destroyForcibly(); break;
            }
            output.write(buffer, 0, count);
          }
        } catch (IOException error) { failed.set(true); }
      });
      if (!child.waitFor(45, TimeUnit.SECONDS)) { child.destroyForcibly(); throw invalid(); }
      reader.join(2000); writer.join(2000);
      if (failed.get() || reader.isAlive() || writer.isAlive() || child.exitValue() != 0) throw invalid();
      try (var result = new DataInputStream(new ByteArrayInputStream(output.toByteArray()))) {
        if (result.readInt() != PdfWorker.MAGIC) throw invalid();
        int pages = result.readInt(), width = result.readInt(), height = result.readInt(), length = result.readInt();
        if (pages < 1 || pages > 20 || width < 1 || height < 1 || (long) width * height > 4_010_000
            || length < 8 || length > PdfWorker.MAX_OUTPUT || result.available() != length) throw invalid();
        return new ProcessedDocument(new ProcessedImage(result.readNBytes(length), "pdf", "application/pdf", width, height),
            pages, DocumentCryptography.hash(bytes));
      }
    } catch (IOException failure) { throw invalid(); }
    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw busy(); }
    finally {
      if (process != null && process.isAlive()) {
        process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly();
      }
      workers.release();
    }
  }

  private List<String> command() throws IOException {
    var result = new ArrayList<String>();
    result.add(Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString());
    result.addAll(List.of("-Xmx256m", "-Djava.awt.headless=true"));
    // Boot's launcher resolves nested libraries in the deployable JAR; development/tests use a small explicit classpath.
    try {
      var launcher = Class.forName("org.springframework.boot.loader.launch.PropertiesLauncher");
      Path jar = Path.of(launcher.getProtectionDomain().getCodeSource().getLocation().toURI());
      if (jar.toString().endsWith(".jar")) {
        result.add("-Dloader.main=" + PdfWorker.class.getName());
        result.addAll(List.of("-cp", jar.toString(), launcher.getName())); return result;
      }
    } catch (ClassNotFoundException | java.net.URISyntaxException ignored) { }
    var paths = new LinkedHashSet<String>();
    for (Class<?> type : List.of(PdfWorker.class, PDDocument.class, org.apache.pdfbox.io.RandomAccessRead.class,
        org.apache.fontbox.FontBoxFont.class, org.apache.commons.logging.LogFactory.class)) {
      try { paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()); }
      catch (Exception failure) { throw new IOException(); }
    }
    result.addAll(List.of("-cp", String.join(java.io.File.pathSeparator, paths), PdfWorker.class.getName()));
    return result;
  }

  /** Input here has already passed consent/authentication, integrity checks and canonical normalization. */
  public DocumentPageResponse page(ImageContent content, int pageNumber) {
    if (pageNumber < 1 || pageNumber > 20) throw new ApiException(HttpStatus.NOT_FOUND, "Document page not found.");
    if (!previews.tryAcquire()) throw busy();
    try {
      byte[] bytes = content.resource().getContentAsByteArray();
      if (!content.contentType().equals("application/pdf")) {
        if (pageNumber != 1) throw new ApiException(HttpStatus.NOT_FOUND, "Document page not found.");
        var image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) throw invalid();
        var response = response(content, pageNumber, 1, content.contentType(), bytes, image.getWidth(), image.getHeight());
        image.flush(); return response;
      }
      try (var document = Loader.loadPDF(bytes)) {
        int count = document.getNumberOfPages();
        if (pageNumber > count) throw new ApiException(HttpStatus.NOT_FOUND, "Document page not found.");
        var resources = document.getPage(pageNumber - 1).getResources();
        for (var name : resources.getXObjectNames()) {
          if (resources.getXObject(name) instanceof PDImageXObject image) {
            if ((long) image.getWidth() * image.getHeight() > 4_010_000) throw invalid();
            var raster = image.getImage(); var output = new ByteArrayOutputStream();
            ImageIO.write(raster, "jpg", output); raster.flush();
            return response(content, pageNumber, count, "image/jpeg", output.toByteArray(), image.getWidth(), image.getHeight());
          }
        }
        throw invalid();
      }
    } catch (IOException failure) { throw invalid(); }
    finally { previews.release(); }
  }

  private DocumentPageResponse response(ImageContent content, int page, int count, String type, byte[] bytes, int width, int height) {
    if (bytes.length > 10 * 1024 * 1024) throw invalid();
    return new DocumentPageResponse(content.id(), page, count, type, DocumentCryptography.hash(bytes), width, height, bytes.length, bytes);
  }
  private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST,
      "This PDF cannot be processed. Use up to 20 unencrypted pages, no XFA forms, and at most 10 MB. Normalized pages must fit within 20 MB."); }
  private static ApiException busy() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Document processing is busy. Retry shortly."); }
}
