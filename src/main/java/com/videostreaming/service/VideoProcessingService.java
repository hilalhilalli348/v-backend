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

                // GERÇEK CMAF - tek ortak segmentler oluştur
                generateTrueCMAF(inputPath, outputDir);

                // Ortak segmentlerden HLS ve DASH manifest'leri oluştur
                generateHLSManifest(outputDir);
                generateDASHManifest(outputDir);

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
                outputDir + "/temp_playlist.m3u8" // Geçici playlist
        };

        logger.info("Generating TRUE CMAF segments (fragmented MP4)");
        logger.info("FFmpeg command: {}", String.join(" ", cmafCommand));
        executeFFmpegCommand(cmafCommand);

        // Geçici playlist'i sil
        try {
            Files.deleteIfExists(Paths.get(outputDir, "temp_playlist.m3u8"));
        } catch (Exception e) {
            logger.warn("Could not delete temp playlist: {}", e.getMessage());
        }
    }

    private void generateHLSManifest(String outputDir) throws IOException {
        // Ortak CMAF segmentlerini kullanan HLS manifest
        StringBuilder playlist = new StringBuilder();
        playlist.append("#EXTM3U\n");
        playlist.append("#EXT-X-VERSION:7\n");
        playlist.append("#EXT-X-TARGETDURATION:4\n");
        playlist.append("#EXT-X-PLAYLIST-TYPE:VOD\n");
        playlist.append("#EXT-X-MAP:URI=\"init.mp4\"\n");

        // Ortak segment dosyalarını say
        Path outputPath = Paths.get(outputDir);
        int segmentCount = 0;

        for (int i = 0; i < 50; i++) { // Max 50 segment kontrolü
            String segmentName = String.format("segment_%03d.m4s", i);
            Path segmentPath = outputPath.resolve(segmentName);
            if (Files.exists(segmentPath)) {
                playlist.append("#EXTINF:4.0,\n");
                playlist.append(segmentName).append("\n");
                segmentCount++;
            } else {
                break;
            }
        }

        playlist.append("#EXT-X-ENDLIST\n");

        // HLS playlist dosyasını yaz
        Path playlistPath = Paths.get(outputDir, "playlist.m3u8");
        Files.writeString(playlistPath, playlist.toString());

        logger.info("Created HLS manifest using {} shared CMAF segments", segmentCount);
    }

    private void generateDASHManifest(String outputDir) throws IOException {
        // Segment sayısını say
        Path outputPath = Paths.get(outputDir);
        int segmentCount = 0;
        for (int i = 0; i < 50; i++) {
            String segmentName = String.format("segment_%03d.m4s", i);
            if (Files.exists(outputPath.resolve(segmentName))) {
                segmentCount++;
            } else {
                break;
            }
        }

        // Basit DASH manifest - tek AdaptationSet (video+audio muxed)
        StringBuilder manifest = new StringBuilder();
        manifest.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        manifest.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" ");
        manifest.append("type=\"static\" ");
        manifest.append("mediaPresentationDuration=\"PT26.4S\" ");
        manifest.append("profiles=\"urn:mpeg:dash:profile:isoff-main:2011\">\n");
        manifest.append("  <Period>\n");

        // Tek AdaptationSet - video+audio birleşik (CMAF şekli)
        manifest.append("    <AdaptationSet mimeType=\"video/mp4\" ");
        manifest.append("codecs=\"avc1.4d401f,mp4a.40.2\" ");
        manifest.append("width=\"720\" height=\"1280\" ");
        manifest.append("frameRate=\"25\" ");
        manifest.append("segmentAlignment=\"true\" ");
        manifest.append("startWithSAP=\"1\">\n");

        manifest.append("      <Representation id=\"muxed\" ");
        manifest.append("bandwidth=\"1833000\" ");
        manifest.append("width=\"720\" height=\"1280\">\n");

        manifest.append("        <SegmentTemplate ");
        manifest.append("timescale=\"1000\" ");
        manifest.append("duration=\"4000\" ");
        manifest.append("initialization=\"init.mp4\" ");
        manifest.append("media=\"segment_$Number%03d$.m4s\" ");
        manifest.append("startNumber=\"0\"/>\n");

        manifest.append("      </Representation>\n");
        manifest.append("    </AdaptationSet>\n");

        manifest.append("  </Period>\n");
        manifest.append("</MPD>\n");

        // DASH manifest dosyasını yaz
        Path manifestPath = Paths.get(outputDir, "manifest.mpd");
        Files.writeString(manifestPath, manifest.toString());

        logger.info("Created DASH manifest with {} muxed segments (video+audio)", segmentCount);
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