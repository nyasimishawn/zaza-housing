package com.zaza.housing.util;

import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.ProcessedImage;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

public final class ImageProcessor {
    private ImageProcessor() { }

    public static ProcessedImage process(MultipartFile file) {
        if (file.isEmpty()) throw invalid("Select a nonempty JPEG or PNG image.");
        if (file.getSize() > 10 * 1024 * 1024) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Images must be at most 10 MB.");
        try {
            byte[] original = file.getBytes();
            return decode(original);
        } catch (IOException error) {
            throw invalid("The image could not be read.");
        }
    }

    private static ProcessedImage decode(byte[] original) {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(original))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("Only valid JPEG and PNG images are supported.");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                boolean png = format.equals("png");
                if (!png && !format.equals("jpeg") && !format.equals("jpg")) throw invalid("Only JPEG and PNG images are supported.");
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 10000 || height > 10000 || (long) width * height > 10000000) {
                    throw invalid("Images must contain at most 10 million pixels.");
                }
                BufferedImage source = reader.read(0);
                int orientation = png ? 1 : ExifOrientation.read(original);
                int orientedWidth = orientation >= 5 ? height : width;
                int orientedHeight = orientation >= 5 ? width : height;
                double scale = Math.min(1.0, 1600.0 / Math.max(width, height));
                int outputWidth = Math.max(1, (int) Math.round(orientedWidth * scale));
                int outputHeight = Math.max(1, (int) Math.round(orientedHeight * scale));
                BufferedImage output = new BufferedImage(outputWidth, outputHeight,
                        png && source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = output.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.scale((double) outputWidth / orientedWidth, (double) outputHeight / orientedHeight);
                    graphics.drawImage(source, ExifOrientation.transform(orientation, width, height), null);
                } finally { graphics.dispose(); source.flush(); }
                try (var bytes = new ByteArrayOutputStream()) {
                    // Re-encode a fresh raster: original EXIF/GPS/text metadata and trailing bytes are not persisted.
                    if (!ImageIO.write(output, png ? "png" : "jpg", bytes)) throw new IOException("Image encoder unavailable");
                    if (bytes.size() > 10 * 1024 * 1024) throw invalid("Processed image exceeds the storage limit.");
                    return new ProcessedImage(bytes.toByteArray(), png ? "png" : "jpg", png ? "image/png" : "image/jpeg", outputWidth, outputHeight);
                } finally { output.flush(); }
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException error) {
            throw invalid("The image could not be decoded. Upload a valid JPEG or PNG.");
        }
    }

    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
}
