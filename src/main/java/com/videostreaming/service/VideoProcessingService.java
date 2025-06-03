package com.videostreaming.service;

import com.videostreaming.model.Video;
import com.videostreaming.repository.VideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class VideoProcessingService {

    private static final Logger logger = LoggerFactory.getLogger(VideoProcessingService.class);

    @Value("${video.storage.path}")
    private String videoStoragePath;

    @Value("${ffmpeg.path}")
    private String ffmpegPath;

    private final VideoRepository videoRepository;

    public VideoProcessingService(VideoRepository videoRepository) {
        this.videoRepository = videoRepository;
    }

    public Mono<Void> processVideo(Video video) {
        return Mono.fromRunnable(() -> {
            try {
                logger.info("Starting TRUE CMAF processing for: {}", video.getFilename());

                video.setStatus("PROCESSING");
                videoRepository.save(video).subscribe();

                String inputPath = Paths.get(videoStoragePath, video.getFilename()).toString();
                String outputDir = Paths.get(videoStoragePath, "processed",
                        video.getFilename().replaceAll("\\.[^.]+$", "")).toString();

                Files.createDirectories(Paths.get(outputDir));

                // Video süresini al
                double videoDuration = getVideoDuration(inputPath);
                logger.info("Video duration: {} seconds", videoDuration);

                // GERÇEK CMAF - tek ortak segmentler oluştur
                generateTrueCMAF(inputPath, outputDir);

                // Ortak segmentlerden HLS ve DASH manifest'leri oluştur
                generateHLSManifest(outputDir, videoDuration);
                generateDASHManifest(outputDir, videoDuration);

                video.setStatus("READY");
                video.setCmafPath(outputDir);
                video.setHlsManifestPath(outputDir + "/playlist.m3u8");
                video.setDashManifestPath(outputDir + "/manifest.mpd");

                videoRepository.save(video).subscribe();

                logger.info("TRUE CMAF processing completed for: {}", video.getFilename());

            } catch (Exception e) {
                logger.error("Error processing video: {}", video.getFilename(), e);
                video.setStatus("ERROR");
                videoRepository.save(video).subscribe();
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private double getVideoDuration(String inputPath) throws IOException, InterruptedException {
        String[] durationCommand = {
                ffmpegPath,
                "-i", inputPath,
                "-f", "null",
                "-"
        };

        ProcessBuilder processBuilder = new ProcessBuilder(durationCommand);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                // Duration satırını ara: Duration: 00:01:23.45, start: 0.000000, bitrate: 1234 kb/s
                if (line.contains("Duration:")) {
                    String durationStr = line.split("Duration: ")[1].split(",")[0].trim();
                    return parseDuration(durationStr);
                }
            }
        }

        process.waitFor(10, TimeUnit.SECONDS);
        return 0.0; // Varsayılan değer
    }

    private double parseDuration(String duration) {
        // Format: HH:MM:SS.ms
        String[] parts = duration.split(":");
        if (parts.length == 3) {
            double hours = Double.parseDouble(parts[0]);
            double minutes = Double.parseDouble(parts[1]);
            double seconds = Double.parseDouble(parts[2]);
            return hours * 3600 + minutes * 60 + seconds;
        }
        return 0.0;
    }

    private void generateTrueCMAF(String inputPath, String outputDir) throws IOException, InterruptedException {
        // HLS ile fragmented MP4 segmentleri oluştur (CMAF uyumlu)
        String[] cmafCommand = {
                ffmpegPath,
                "-i", inputPath,
                "-c:v", "libx264",
                "-c:a", "aac",
                "-preset", "fast",
                "-crf", "23",
                "-f", "hls",
                "-hls_time", "4",
                "-hls_playlist_type", "vod",
                "-hls_segment_type", "fmp4",
                "-hls_fmp4_init_filename", "init.mp4",
                "-hls_segment_filename", outputDir + "/segment_%03d.m4s",
                "-movflags", "+frag_keyframe+empty_moov+default_base_moof",
                outputDir + "/ffmpeg_playlist.m3u8" // FFmpeg'in orijinal playlist'i
        };

        logger.info("Generating TRUE CMAF segments (fragmented MP4)");
        logger.info("FFmpeg command: {}", String.join(" ", cmafCommand));
        executeFFmpegCommand(cmafCommand);

        // FFmpeg playlist'ini sakla - gerçek süreler burada
        logger.info("FFmpeg generated playlist saved as ffmpeg_playlist.m3u8");
    }

    private void generateHLSManifest(String outputDir, double videoDuration) throws IOException, InterruptedException {
        // FFmpeg'in orijinal playlist'inden gerçek sürelerini oku
        double[] segmentDurations = parseFFmpegPlaylist(outputDir);

        if (segmentDurations.length == 0) {
            logger.warn("Could not parse FFmpeg playlist, using calculated durations");
            segmentDurations = getCalculatedSegmentDurations(outputDir, videoDuration);
        }

        StringBuilder playlist = new StringBuilder();
        playlist.append("#EXTM3U\n");
        playlist.append("#EXT-X-VERSION:7\n");
        playlist.append("#EXT-X-TARGETDURATION:5\n");
        playlist.append("#EXT-X-PLAYLIST-TYPE:VOD\n");
        playlist.append("#EXT-X-MAP:URI=\"init.mp4\"\n");

        double totalDuration = 0.0;
        for (int i = 0; i < segmentDurations.length; i++) {
            String segmentName = String.format("segment_%03d.m4s", i);
            double duration = segmentDurations[i];

            playlist.append(String.format("#EXTINF:%.6f,\n", duration));
            playlist.append(segmentName).append("\n");
            totalDuration += duration;
        }

        playlist.append("#EXT-X-ENDLIST\n");

        // HLS playlist dosyasını yaz
        Path playlistPath = Paths.get(outputDir, "playlist.m3u8");
        Files.writeString(playlistPath, playlist.toString());

        logger.info("Created HLS manifest with {} segments, total duration: {:.3f}s (original: {:.3f}s)",
                segmentDurations.length, totalDuration, videoDuration);

        // FFmpeg playlist'ini temizle
        try {
            Files.deleteIfExists(Paths.get(outputDir, "ffmpeg_playlist.m3u8"));
        } catch (Exception e) {
            logger.warn("Could not delete FFmpeg playlist: {}", e.getMessage());
        }
    }

    private double[] parseFFmpegPlaylist(String outputDir) throws IOException {
        Path playlistPath = Paths.get(outputDir, "ffmpeg_playlist.m3u8");
        if (!Files.exists(playlistPath)) {
            logger.warn("FFmpeg playlist not found: {}", playlistPath);
            return new double[0];
        }

        List<Double> durations = new ArrayList<>();
        List<String> lines = Files.readAllLines(playlistPath);

        for (String line : lines) {
            if (line.startsWith("#EXTINF:")) {
                try {
                    // #EXTINF:4.000000, formatından süreyi çıkar
                    String durationStr = line.substring(8, line.indexOf(','));
                    double duration = Double.parseDouble(durationStr);
                    durations.add(duration);
                } catch (Exception e) {
                    logger.warn("Could not parse duration from line: {}", line);
                }
            }
        }

        logger.info("Parsed {} segment durations from FFmpeg playlist", durations.size());
        return durations.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private double[] getCalculatedSegmentDurations(String outputDir, double videoDuration) throws IOException {
        // Fallback: Manuel hesaplama
        Path outputPath = Paths.get(outputDir);
        int segmentCount = 0;

        for (int i = 0; i < 1000; i++) {
            String segmentName = String.format("segment_%03d.m4s", i);
            if (Files.exists(outputPath.resolve(segmentName))) {
                segmentCount++;
            } else {
                break;
            }
        }

        double[] durations = new double[segmentCount];

        for (int i = 0; i < segmentCount; i++) {
            if (i == segmentCount - 1) {
                // Son segment
                double usedDuration = i * 4.0;
                double remainingDuration = videoDuration - usedDuration;
                durations[i] = Math.max(0.033333, Math.min(4.0, remainingDuration));
            } else {
                durations[i] = 4.0;
            }
        }

        return durations;
    }



    private void generateDASHManifest(String outputDir, double videoDuration) throws IOException {
        // FFmpeg playlist'inden gerçek sürelerini oku (eğer varsa)
        double[] segmentDurations;
        try {
            segmentDurations = parseFFmpegPlaylist(outputDir);
            if (segmentDurations.length == 0) {
                segmentDurations = getCalculatedSegmentDurations(outputDir, videoDuration);
            }
        } catch (Exception e) {
            logger.warn("Could not parse segment durations, using calculated values");
            segmentDurations = getCalculatedSegmentDurations(outputDir, videoDuration);
        }

        // Video süresini ISO 8601 formatına çevir
        String isoDuration = formatDurationToISO(videoDuration);

        // DASH manifest - SegmentTimeline ile gerçek süreler
        StringBuilder manifest = new StringBuilder();
        manifest.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        manifest.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" ");
        manifest.append("type=\"static\" ");
        manifest.append("mediaPresentationDuration=\"").append(isoDuration).append("\" ");
        manifest.append("profiles=\"urn:mpeg:dash:profile:isoff-main:2011\">\n");
        manifest.append("  <Period>\n");

        manifest.append("    <AdaptationSet mimeType=\"video/mp4\" ");
        manifest.append("codecs=\"avc1.4d401f,mp4a.40.2\" ");
        manifest.append("width=\"720\" height=\"1280\" ");
        manifest.append("frameRate=\"25\" ");
        manifest.append("segmentAlignment=\"true\" ");
        manifest.append("startWithSAP=\"1\">\n");

        manifest.append("      <Representation id=\"muxed\" ");
        manifest.append("bandwidth=\"1833000\" ");
        manifest.append("width=\"720\" height=\"1280\">\n");

        // SegmentTimeline ile her segmentin gerçek süresi
        manifest.append("        <SegmentTemplate ");
        manifest.append("timescale=\"1000\" ");
        manifest.append("initialization=\"init.mp4\" ");
        manifest.append("media=\"segment_$Number%03d$.m4s\" ");
        manifest.append("startNumber=\"0\">\n");

        manifest.append("          <SegmentTimeline>\n");

        long currentTime = 0;
        for (int i = 0; i < segmentDurations.length; i++) {
            long durationMs = Math.round(segmentDurations[i] * 1000);
            manifest.append("            <S t=\"").append(currentTime).append("\" d=\"").append(durationMs).append("\"/>\n");
            currentTime += durationMs;
        }

        manifest.append("          </SegmentTimeline>\n");
        manifest.append("        </SegmentTemplate>\n");

        manifest.append("      </Representation>\n");
        manifest.append("    </AdaptationSet>\n");

        manifest.append("  </Period>\n");
        manifest.append("</MPD>\n");

        // DASH manifest dosyasını yaz
        Path manifestPath = Paths.get(outputDir, "manifest.mpd");
        Files.writeString(manifestPath, manifest.toString());

        double totalDuration = Arrays.stream(segmentDurations).sum();
        logger.info("Created DASH manifest with {} segments, total duration: {:.3f}s",
                segmentDurations.length, totalDuration);
    }

    private String formatDurationToISO(double seconds) {
        int hours = (int) (seconds / 3600);
        int minutes = (int) ((seconds % 3600) / 60);
        double remainingSeconds = seconds % 60;

        StringBuilder iso = new StringBuilder("PT");
        if (hours > 0) {
            iso.append(hours).append("H");
        }
        if (minutes > 0) {
            iso.append(minutes).append("M");
        }
        if (remainingSeconds > 0) {
            iso.append(String.format("%.2f", remainingSeconds)).append("S");
        }

        return iso.toString();
    }

    private void executeFFmpegCommand(String[] command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.debug("FFmpeg: {}", line);
                output.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(30, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("FFmpeg process timed out");
        }

        if (process.exitValue() != 0) {
            logger.error("FFmpeg output: {}", output.toString());
            throw new RuntimeException("FFmpeg process failed with exit code: " + process.exitValue());
        }

        logger.info("FFmpeg completed successfully");
    }
}