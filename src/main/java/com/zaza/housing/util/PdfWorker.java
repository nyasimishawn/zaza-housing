package com.zaza.housing.util;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.DefaultResourceCache;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/** Runs in a separate, heap/time-bounded JVM. Only generated raster pages leave this process. */
public final class PdfWorker {
  public static final int MAGIC = 0x5a504431;
  public static final int MAX_INPUT = 10 * 1024 * 1024;
  public static final int MAX_OUTPUT = 20 * 1024 * 1024;
  private PdfWorker() {}

  public static void main(String[] args) {
    try {
      byte[] source = System.in.readNBytes(MAX_INPUT + 1);
      if (source.length == 0 || source.length > MAX_INPUT) throw new IOException();
      normalize(source);
    } catch (Throwable failure) {
      // Parser messages can contain private text. Never echo input, paths or parser details.
      System.exit(2);
    }
  }

  private static void normalize(byte[] source) throws IOException {
    try (var input = Loader.loadPDF(source); var output = new PDDocument()) {
      if (input.isEncrypted() || input.getNumberOfPages() < 1 || input.getNumberOfPages() > 20)
        throw new IOException();
      if (input.getDocumentCatalog().getAcroForm() != null
          && input.getDocumentCatalog().getAcroForm().hasXFA()) throw new IOException();
      input.setResourceCache(new DefaultResourceCache() {
        @Override public void put(org.apache.pdfbox.cos.COSObject key, PDXObject object) {}
      });
      var renderer = new PDFRenderer(input);
      renderer.setSubsamplingAllowed(true);
      long totalPixels = 0;
      int firstWidth = 0, firstHeight = 0;
      for (int i = 0; i < input.getNumberOfPages(); i++) {
        var page = input.getPage(i);
        var box = page.getCropBox();
        float width = box.getWidth(), height = box.getHeight();
        if (!Float.isFinite(width) || !Float.isFinite(height) || width < 36 || height < 36
            || width > 14400 || height > 14400 || !Float.isFinite(page.getUserUnit())
            || page.getUserUnit() != 1) throw new IOException();
        float dpi = Math.min(160, 2000 * 72 / Math.max(width, height));
        long estimate = (long) Math.ceil(width * dpi / 72) * (long) Math.ceil(height * dpi / 72);
        totalPixels += estimate;
        if (estimate > 4_010_000 || totalPixels > 60_000_000) throw new IOException();
        BufferedImage image = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
        if (i == 0) { firstWidth = image.getWidth(); firstHeight = image.getHeight(); }
        boolean rotated = Math.floorMod(page.getRotation(), 180) == 90;
        var cleanPage = new PDPage(new PDRectangle(rotated ? height : width, rotated ? width : height));
        output.addPage(cleanPage);
        var jpeg = JPEGFactory.createFromImage(output, image, .86f);
        try (var content = new PDPageContentStream(output, cleanPage)) {
          content.drawImage(jpeg, 0, 0, cleanPage.getMediaBox().getWidth(), cleanPage.getMediaBox().getHeight());
        }
        image.flush();
      }
      var id = new COSArray();
      var hash = java.security.MessageDigest.getInstance("SHA-256");
      byte[] stable = java.util.Arrays.copyOf(hash.digest(source), 16);
      id.add(new COSString(stable)); id.add(new COSString(stable));
      output.getDocument().getTrailer().setItem(org.apache.pdfbox.cos.COSName.ID, id);
      var bytes = new ByteArrayOutputStream();
      output.save(new OutputStream() {
        @Override public void write(int value) throws IOException {
          if (bytes.size() >= MAX_OUTPUT) throw new IOException(); bytes.write(value);
        }
        @Override public void write(byte[] values, int offset, int length) throws IOException {
          if ((long) bytes.size() + length > MAX_OUTPUT) throw new IOException(); bytes.write(values, offset, length);
        }
      });
      var result = new DataOutputStream(System.out);
      result.writeInt(MAGIC); result.writeInt(output.getNumberOfPages());
      result.writeInt(firstWidth); result.writeInt(firstHeight);
      result.writeInt(bytes.size()); bytes.writeTo(result); result.flush();
    } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
  }
}
