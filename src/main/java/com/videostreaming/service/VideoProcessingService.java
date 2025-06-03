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
import java.io.File;
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
                logger.info("Starting CMAF video processing for: {}", video.getFilename());

                // Update status to PROCESSING
                video.setStatus("PROCESSING");
                videoRepository.save(video).subscribe();

                String inputPath = Paths.get(videoStoragePath, video.getFilename()).toString();
                String outputDir = Paths.get(videoStoragePath, "processed",
                        video.getFilename().replaceAll("\\.[^.]+$", "")).toString();

                // Create output directory
                Files.createDirectories(Paths.get(outputDir));

                // Generate shared CMAF segments
                generateSharedCMAF(inputPath, outputDir, video);

                // Generate HLS playlist using shared segments
                generateHLSPlaylist(outputDir, video);

                // Update video status
                video.setStatus("READY");
                video.setCmafPath(outputDir);
                video.setHlsManifestPath(outputDir + "/playlist.m3u8");
                video.setDashManifestPath(outputDir + "/manifest.mpd");

                videoRepository.save(video).subscribe();

                logger.info("CMAF video processing completed for: {}", video.getFilename());

            } catch (Exception e) {
                logger.error("Error processing video: {}", video.getFilename(), e);
                video.setStatus("ERROR");
                videoRepository.save(video).subscribe();
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private void generateSharedCMAF(String inputPath, String outputDir, Video video) throws IOException, InterruptedException {
        // CMAF formatında ortak segmentler oluştur - hem HLS hem DASH uyumlu
        String[] cmafCommand = {
                ffmpegPath,
                "-i", inputPath,
                "-c:v", "libx264",
                "-c:a", "aac",
                "-preset", "fast",
                "-crf", "23",
                "-movflags", "+frag_keyframe+empty_moov+default_base_moof",
                "-f", "dash",
                "-seg_duration", "4",
                "-use_template", "1", // Template kullan
                "-use_timeline", "1",
                "-dash_segment_type", "mp4",
                "-init_seg_name", "init_$RepresentationID$.mp4",
                "-media_seg_name", "segment_$RepresentationID$_$Number$.m4s",
                "-adaptation_sets", "id=0,streams=v id=1,streams=a",
                outputDir + "/manifest.mpd"
        };

        logger.info("Generating shared CMAF segments");
        logger.info("FFmpeg command: {}", String.join(" ", cmafCommand));
        executeFFmpegCommand(cmafCommand);
    }

    private void generateHLSPlaylist(String outputDir, Video video) throws IOException, InterruptedException {
        // Ortak CMAF segmentlerini kullanarak HLS playlist oluştur
        String playlistContent = createHLSPlaylist(outputDir);

        // HLS playlist dosyasını yaz
        Path playlistPath = Paths.get(outputDir, "playlist.m3u8");
        Files.writeString(playlistPath, playlistContent);

        logger.info("HLS playlist created using shared CMAF segments");
    }

    private String createHLSPlaylist(String outputDir) throws IOException {
        StringBuilder playlist = new StringBuilder();
        playlist.append("#EXTM3U\n");
        playlist.append("#EXT-X-VERSION:7\n");
        playlist.append("#EXT-X-TARGETDURATION:4\n");
        playlist.append("#EXT-X-PLAYLIST-TYPE:VOD\n");
        playlist.append("#EXT-X-MAP:URI=\"init_0.mp4\"\n"); // Video init dosyası

        // Video segmentlerini say (RepresentationID=0 video için)
        Path outputPath = Paths.get(outputDir);
        int segmentCount = 0;

        // segment_0_1.m4s, segment_0_2.m4s... dosyalarını say (video stream)
        for (int i = 1; i <= 20; i++) { // Max 20 segment kontrolü
            Path segmentPath = outputPath.resolve("segment_0_" + i + ".m4s");
            if (Files.exists(segmentPath)) {
                playlist.append("#EXTINF:4.0,\n");
                playlist.append("segment_0_").append(i).append(".m4s\n");
                segmentCount++;
            } else {
                break;
            }
        }

        playlist.append("#EXT-X-ENDLIST\n");

        logger.info("Created HLS playlist with {} video segments", segmentCount);
        return playlist.toString();
    }

    private void executeFFmpegCommand(String[] command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        // Read output
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

    public Mono<String> getVideoInfo(String filePath) {
        return Mono.fromCallable(() -> {
            String[] command = {
                    ffmpegPath,
                    "-i", filePath,
                    "-f", "null", "-"
            };

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            process.waitFor();
            return output.toString();
        }).subscribeOn(Schedulers.boundedElastic());
    }
}