package com.zaza.housing.service;

import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.ProcessedVideo;
import com.zaza.housing.util.MediaProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class VideoProcessingService {
    public static final long MAX_BYTES = 50L * 1024 * 1024;
    private final String ffmpeg;
    private final String ffprobe;
    private final ObjectMapper json;
    private final Semaphore encoders = new Semaphore(2);
    public VideoProcessingService(@Value("${zaza.videos.ffmpeg}") String ffmpeg,
            @Value("${zaza.videos.ffprobe}") String ffprobe, ObjectMapper json) {
        this.ffmpeg = ffmpeg; this.ffprobe = ffprobe; this.json = json;
    }

    public ProcessedVideo process(MultipartFile file, Path workspace) throws IOException {
        if (file.isEmpty()) throw invalid("Upload a non-empty MP4 or MOV clip.");
        if (file.getSize() > MAX_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Videos must be at most 50 MB.");
        if (!encoders.tryAcquire()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Video processing is busy. Try again later.");
        try {
            Path source = workspace.resolve("source.upload");
            // Bound the actual bytes copied, rather than trusting multipart metadata.
            try (var input = file.getInputStream(); var output = Files.newOutputStream(source)) {
                byte[] buffer = new byte[8192]; long total = 0; int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Videos must be at most 50 MB.");
                    output.write(buffer, 0, count);
                }
            }
            try (var input = Files.newInputStream(source)) {
                byte[] header = input.readNBytes(12);
                if (header.length < 12 || header[4] != 'f' || header[5] != 't' || header[6] != 'y' || header[7] != 'p') {
                    throw invalid("Upload an MP4 or MOV container. Filenames and MIME types do not determine its format.");
                }
            }
            JsonNode input = probe(source);
            validate(input, false);
            Path output = workspace.resolve("processed.mp4");
            MediaProcessRunner.run(List.of(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-xerror",
                    "-filter_threads", "1", "-threads", "2", "-protocol_whitelist", "file,pipe", "-format_whitelist", "mov",
                    "-i", source.toString(), "-map", "0:v:0", "-map", "0:a:0?", "-map_metadata", "-1",
                    "-map_metadata:s", "-1", "-map_chapters", "-1", "-t", "120", "-vf",
                    "scale=w='min(1280,iw)':h='min(1280,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2:reset_sar=1,fps=30",
                    "-c:v", "libx264", "-threads", "2", "-preset", "fast", "-crf", "24", "-maxrate", "2500k", "-bufsize", "5000k",
                    "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k", "-ac", "2", "-ar", "48000",
                    "-movflags", "+faststart", "-y", output.toString()), Duration.ofSeconds(90));
            JsonNode result = probe(output);
            JsonNode video = validate(result, true);
            long size = Files.size(output);
            if (size == 0 || size > MAX_BYTES) throw invalid("The processed video is too large. Try a shorter clip.");
            return new ProcessedVideo(output, video.path("width").asInt(), video.path("height").asInt(),
                    duration(result), size);
        } finally { encoders.release(); }
    }

    private JsonNode probe(Path source) {
        String output = MediaProcessRunner.run(List.of(ffprobe, "-v", "error", "-protocol_whitelist", "file,pipe",
                "-format_whitelist", "mov", "-show_entries",
                "format=duration,format_name:stream=codec_type,codec_name,width,height,r_frame_rate,pix_fmt,duration",
                "-of", "json", source.toString()), Duration.ofSeconds(10));
        try { return json.readTree(output); }
        catch (RuntimeException error) { throw invalid("The video headers cannot be read."); }
    }
    private JsonNode validate(JsonNode data, boolean processed) {
        double seconds = duration(data);
        if (!Double.isFinite(seconds) || seconds <= 0 || seconds > (processed ? 120.05 : 120)) {
            throw invalid("Videos must have a known duration of at most 120 seconds.");
        }
        JsonNode video = null; int audio = 0;
        for (JsonNode stream : data.path("streams")) {
            String type = stream.path("codec_type").asText();
            if (type.equals("video")) {
                if (video != null) throw invalid("Use a clip with one video track.");
                video = stream;
            } else if (type.equals("audio")) {
                if (++audio > 1) throw invalid("Use at most one audio track.");
                if (!List.of("aac", "mp3", "pcm_s16le", "pcm_s24le").contains(stream.path("codec_name").asText())) {
                    throw invalid("Use AAC, MP3, or PCM audio.");
                }
            } else { throw invalid("Remove subtitles and data tracks before uploading the video."); }
            double streamDuration = stream.path("duration").asDouble(seconds);
            if (!Double.isFinite(streamDuration) || streamDuration <= 0 || streamDuration > (processed ? 120.05 : 120)) {
                throw invalid("Every video and audio track must be at most 120 seconds.");
            }
        }
        if (video == null || !List.of("h264", "hevc", "mpeg4").contains(video.path("codec_name").asText())) {
            throw invalid("Use H.264, H.265, or MPEG-4 video.");
        }
        int width = video.path("width").asInt(); int height = video.path("height").asInt();
        if (width < 2 || height < 2 || width > 4096 || height > 4096 || (long) width * height > 8847360) {
            throw invalid("Video frames must fit within 4K resolution.");
        }
        String[] rate = video.path("r_frame_rate").asText().split("/");
        double fps;
        try { fps = Double.parseDouble(rate[0]) / (rate.length == 2 ? Double.parseDouble(rate[1]) : 1); }
        catch (NumberFormatException error) { throw invalid("Use a video with a known frame rate."); }
        if (!Double.isFinite(fps) || fps <= 0 || fps > 120) throw invalid("Use a video at no more than 120 frames per second.");
        if (processed && (width > 1280 || height > 1280 || width % 2 != 0 || height % 2 != 0
                || !video.path("codec_name").asText().equals("h264") || !video.path("pix_fmt").asText().equals("yuv420p") || fps > 30.01)) {
            throw invalid("The video could not be converted for browser playback.");
        }
        return video;
    }
    private static double duration(JsonNode data) { return data.path("format").path("duration").asDouble(Double.NaN); }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
}
