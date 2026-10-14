package com.zaza.housing.util;

import com.zaza.housing.exception.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;

public final class MediaProcessRunner {
    private MediaProcessRunner() { }
    public static String run(List<String> command, Duration timeout) {
        final Process process;
        try { process = new ProcessBuilder(command).redirectErrorStream(true).start(); }
        catch (IOException error) { throw unavailable("Video tools are unavailable. Configure FFmpeg and FFprobe."); }
        var bytes = new ByteArrayOutputStream();
        var overflow = new AtomicBoolean();
        var readFailure = new AtomicBoolean();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var input = process.getInputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 131072) { overflow.set(true); process.destroyForcibly(); break; }
                    bytes.write(buffer, 0, count);
                }
            } catch (IOException error) { readFailure.set(true); }
        });
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                stop(process);
                throw unavailable("Video processing timed out. Try a shorter clip.");
            }
            reader.join(2000);
            if (reader.isAlive() || readFailure.get() || overflow.get() || process.exitValue() != 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "The video cannot be decoded. Upload a valid MP4 or MOV clip.");
            }
            return bytes.toString(StandardCharsets.UTF_8);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable("Video processing was interrupted. Try again later.");
        } finally {
            stop(process);
            try { process.getInputStream().close(); } catch (IOException ignored) { }
        }
    }
    private static void stop(Process process) {
        process.descendants().forEach(child -> child.destroyForcibly());
        if (process.isAlive()) {
            process.destroyForcibly();
            // Windows must release input/output handles before upload workspace cleanup.
            try { process.waitFor(3, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
    }
    private static ApiException unavailable(String message) { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, message); }
}
