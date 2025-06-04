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

    // TikTok tarzı kalite seviyeleri
    private static final QualityLevel[] QUALITY_LEVELS = {
            new QualityLevel("1080p", 1920, 1080, "3500k", "128k"),
            new QualityLevel("720p", 1280, 720, "2500k", "128k"),
            new QualityLevel("480p", 854, 480, "1500k", "96k"),    // Dinamik hesaplama ile güncellenecek
            new QualityLevel("360p", 640, 360, "800k", "96k"),
            new QualityLevel("240p", 426, 240, "400k", "64k")
    };

    public VideoProcessingService(VideoRepository videoRepository) {
        this.videoRepository = videoRepository;
    }

    public Mono<Void> processVideo(Video video) {
        return Mono.fromRunnable(() -> {
            try {
                logger.info("Starting multi-quality CMAF processing for: {}", video.getFilename());

                video.setStatus("PROCESSING");
                videoRepository.save(video).subscribe();

                String inputPath = Paths.get(videoStoragePath, video.getFilename()).toString();
                String outputDir = Paths.get(videoStoragePath, "processed",
                        video.getFilename().replaceAll("\\.[^.]+$", "")).toString();

                Files.createDirectories(Paths.get(outputDir));

                // Video bilgilerini al
                VideoInfo videoInfo = getVideoInfo(inputPath);
                logger.info("Video info - Duration: {}s, Resolution: {}x{}",
                        videoInfo.duration, videoInfo.width, videoInfo.height);

                // Uygun kalite seviyelerini belirle
                List<QualityLevel> targetQualities = determineTargetQualities(videoInfo);
                logger.info("Target qualities: {}", targetQualities.stream()
                        .map(q -> q.name).reduce((a, b) -> a + ", " + b).orElse("none"));

                // Her kalite için CMAF segmentleri oluştur
                List<QualityLevel> successfulQualities = new ArrayList<>();
                for (QualityLevel quality : targetQualities) {
                    try {
                        logger.info("Starting encoding for quality: {}", quality.name);
                        generateQualityCMAF(inputPath, outputDir, quality, videoInfo);

                        // Başarılı encoding kontrolü
                        String qualityDir = outputDir + "/" + quality.name;
                        Path initFile = Paths.get(qualityDir, "init.mp4");
                        Path playlistFile = Paths.get(qualityDir, "playlist.m3u8");

                        if (Files.exists(initFile) && Files.exists(playlistFile)) {
                            successfulQualities.add(quality);
                            logger.info("Successfully completed encoding for quality: {}", quality.name);
                        } else {
                            logger.error("Encoding failed for quality: {} - Missing output files", quality.name);
                            logger.error("Init file exists: {}, Playlist exists: {}",
                                    Files.exists(initFile), Files.exists(playlistFile));
                        }
                    } catch (Exception e) {
                        logger.error("Failed to encode quality: {} - Error: {}", quality.name, e.getMessage(), e);
                        // Bu kaliteyi atla, diğerlerine devam et
                        continue;
                    }
                }

                // Sadece başarılı kaliteleri manifest'lerde kullan
                if (successfulQualities.isEmpty()) {
                    throw new RuntimeException("No qualities were successfully encoded!");
                }

                logger.info("Successfully encoded qualities: {}", successfulQualities.stream()
                        .map(q -> q.name).reduce((a, b) -> a + ", " + b).orElse("none"));

                // Master manifests oluştur
                generateMasterHLSManifest(outputDir, successfulQualities, videoInfo);
                generateMasterDASHManifest(outputDir, successfulQualities, videoInfo);

                // Video entity güncelle
                video.setStatus("READY");
                video.setCmafPath(outputDir);
                video.setHlsManifestPath(outputDir + "/master.m3u8");
                video.setDashManifestPath(outputDir + "/master.mpd");

                videoRepository.save(video).subscribe();

                logger.info("Multi-quality CMAF processing completed for: {}", video.getFilename());

            } catch (Exception e) {
                logger.error("Error processing video: {}", video.getFilename(), e);
                video.setStatus("ERROR");
                videoRepository.save(video).subscribe();
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private VideoInfo getVideoInfo(String inputPath) throws IOException, InterruptedException {
        String[] probeCommand = {
                ffmpegPath.replace("ffmpeg", "ffprobe"),
                "-v", "quiet",
                "-print_format", "json",
                "-show_format",
                "-show_streams",
                inputPath
        };

        ProcessBuilder processBuilder = new ProcessBuilder(probeCommand);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        process.waitFor(10, TimeUnit.SECONDS);

        String result = output.toString();
        logger.debug("FFprobe raw output: {}", result);

        // Duration parsing - format seviyesinden al
        double duration = 0.0;
        if (result.contains("\"duration\"")) {
            try {
                // İlk duration değerini al (genellikle format seviyesinden)
                String durationMatch = result.split("\"duration\"\\s*:\\s*\"")[1].split("\"")[0];
                duration = Double.parseDouble(durationMatch);
            } catch (Exception e) {
                logger.warn("Could not parse duration: {}", e.getMessage());
            }
        }

        // Video stream bilgileri - video codec_type'ını ara
        int width = 0, height = 0;

        // JSON'u satır satır parse et ve video stream'i bul
        String[] lines = result.split("\n");
        boolean inVideoStream = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // Video stream başlangıcını tespit et
            if (line.contains("\"codec_type\"") && line.contains("\"video\"")) {
                inVideoStream = true;
                logger.debug("Found video stream at line: {}", line);
            }

            // Video stream içindeyken width ve height ara
            if (inVideoStream) {
                if (line.contains("\"width\"")) {
                    try {
                        String widthStr = line.split("\"width\"\\s*:\\s*")[1].split("[,\\s}]")[0];
                        width = Integer.parseInt(widthStr);
                        logger.debug("Parsed width: {}", width);
                    } catch (Exception e) {
                        logger.warn("Could not parse width from line: {}", line);
                    }
                }

                if (line.contains("\"height\"")) {
                    try {
                        String heightStr = line.split("\"height\"\\s*:\\s*")[1].split("[,\\s}]")[0];
                        height = Integer.parseInt(heightStr);
                        logger.debug("Parsed height: {}", height);
                    } catch (Exception e) {
                        logger.warn("Could not parse height from line: {}", line);
                    }
                }

                // Video stream sona erdi (bir sonraki stream başladı veya streams array bitti)
                if ((line.contains("\"codec_type\"") && !line.contains("\"video\"")) ||
                        line.contains("}") && !line.contains("\"")) {
                    break;
                }
            }
        }

        // Fallback: FFprobe basit format ile tekrar dene
        if (width == 0 || height == 0) {
            logger.warn("JSON parsing failed, trying simple ffprobe format");
            String[] simpleProbeCommand = {
                    ffmpegPath.replace("ffmpeg", "ffprobe"),
                    "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=width,height",
                    "-of", "csv=s=x:p=0",
                    inputPath
            };

            ProcessBuilder simpleBuilder = new ProcessBuilder(simpleProbeCommand);
            Process simpleProcess = simpleBuilder.start();

            try (BufferedReader simpleReader = new BufferedReader(new InputStreamReader(simpleProcess.getInputStream()))) {
                String dimensionLine = simpleReader.readLine();
                if (dimensionLine != null && dimensionLine.contains("x")) {
                    String[] dimensions = dimensionLine.trim().split("x");
                    if (dimensions.length == 2) {
                        width = Integer.parseInt(dimensions[0]);
                        height = Integer.parseInt(dimensions[1]);
                        logger.info("Fallback parsing successful: {}x{}", width, height);
                    }
                }
            } catch (Exception e) {
                logger.error("Fallback parsing also failed: {}", e.getMessage());
            }

            simpleProcess.waitFor(5, TimeUnit.SECONDS);
        }

        logger.info("Final parsed video info - Duration: {}s, Resolution: {}x{}", duration, width, height);

        return new VideoInfo(duration, width, height);
    }

    private List<QualityLevel> determineTargetQualities(VideoInfo videoInfo) {
        List<QualityLevel> targetQualities = new ArrayList<>();

        // Orijinal çözünürlük temel alınarak uygun kaliteleri seç
        int originalHeight = videoInfo.height;

        logger.info("Original video resolution: {}x{}", videoInfo.width, originalHeight);

        for (QualityLevel quality : QUALITY_LEVELS) {
            // Orijinalden büyük kalite üretme
            if (quality.height <= originalHeight) {
                // Dinamik boyut hesaplama ile kaliteyi ayarla
                QualityLevel adjustedQuality = calculateOptimalDimensions(quality, videoInfo);
                targetQualities.add(adjustedQuality);
                logger.info("Added quality: {} ({}x{} -> {}x{})",
                        quality.name, quality.width, quality.height,
                        adjustedQuality.width, adjustedQuality.height);
            }
        }

        // TikTok stratejisi: Her zaman minimum 240p ve 360p olsun
        if (targetQualities.isEmpty() || originalHeight < 360) {
            // Çok düşük çözünürlüklü videolar için bile 240p ve 360p üret
            targetQualities.clear();
            targetQualities.add(calculateOptimalDimensions(
                    new QualityLevel("360p", 640, 360, "800k", "96k"), videoInfo));
            targetQualities.add(calculateOptimalDimensions(
                    new QualityLevel("240p", 426, 240, "400k", "64k"), videoInfo));
            logger.info("Low resolution video - forcing 360p and 240p encoding");
        }

        // En az 2 kalite garantisi
        if (targetQualities.size() == 1 && originalHeight >= 480) {
            // Tek kalite varsa ve video yeterince büyükse, bir alt kaliteyi de ekle
            if (originalHeight >= 720) {
                targetQualities.add(calculateOptimalDimensions(
                        new QualityLevel("480p", 854, 480, "1500k", "96k"), videoInfo));
            }
            targetQualities.add(calculateOptimalDimensions(
                    new QualityLevel("360p", 640, 360, "800k", "96k"), videoInfo));
        }

        logger.info("Final target qualities: {}", targetQualities.stream()
                .map(q -> q.name + "(" + q.width + "x" + q.height + ")")
                .reduce((a, b) -> a + ", " + b).orElse("none"));

        return targetQualities;
    }

    /**
     * Dinamik boyut hesaplama - aspect ratio koruyarak çift sayı garantisi
     */
    private QualityLevel calculateOptimalDimensions(QualityLevel targetQuality, VideoInfo videoInfo) {
        // Orijinal aspect ratio hesapla
        double originalAspectRatio = (double) videoInfo.width / videoInfo.height;

        logger.debug("Calculating optimal dimensions for {}: target={}x{}, original={}x{}, aspect_ratio={}",
                targetQuality.name, targetQuality.width, targetQuality.height,
                videoInfo.width, videoInfo.height, originalAspectRatio);

        // Hedef boyutları aspect ratio'ya göre ayarla
        int newWidth = targetQuality.width;
        int newHeight = targetQuality.height;

        // Aspect ratio'yu koruyarak boyutları hesapla
        int calculatedWidth = (int) Math.round(newHeight * originalAspectRatio);
        int calculatedHeight = (int) Math.round(newWidth / originalAspectRatio);

        // Hangi yaklaşımın daha uygun olduğunu belirle
        if (calculatedWidth <= newWidth) {
            // Yükseklik sabit, genişlik hesaplanır
            newWidth = calculatedWidth;
        } else {
            // Genişlik sabit, yükseklik hesaplanır
            newHeight = calculatedHeight;
        }

        // Çift sayı garantisi (H.264 gereksinimi)
        newWidth = makeEven(newWidth);
        newHeight = makeEven(newHeight);

        // Minimum boyut kontrolü (çok küçük boyutları engelle)
        if (newWidth < 240) newWidth = 240;
        if (newHeight < 144) newHeight = 144;

        // Maksimum boyut kontrolü (orijinalden büyük boyutları engelle)
        if (newWidth > videoInfo.width) {
            newWidth = makeEven(videoInfo.width);
            newHeight = makeEven((int) Math.round(newWidth / originalAspectRatio));
        }
        if (newHeight > videoInfo.height) {
            newHeight = makeEven(videoInfo.height);
            newWidth = makeEven((int) Math.round(newHeight * originalAspectRatio));
        }

        logger.debug("Calculated optimal dimensions for {}: {}x{}",
                targetQuality.name, newWidth, newHeight);

        // Yeni boyutlarla kalite objesi oluştur
        return new QualityLevel(
                targetQuality.name,
                newWidth,
                newHeight,
                targetQuality.videoBitrate,
                targetQuality.audioBitrate
        );
    }

    /**
     * Sayıyı çift sayı yapar (H.264 gereksinimi)
     */
    private int makeEven(int number) {
        return (number % 2 == 0) ? number : number - 1;
    }

    /**
     * Dinamik scale filtresi oluşturma
     */
    private String calculateScaleFilter(QualityLevel quality, VideoInfo videoInfo) {
        // Boyutlar zaten calculateOptimalDimensions ile hesaplanmış
        return String.format("scale=%d:%d:flags=lanczos", quality.width, quality.height);
    }

    private void generateQualityCMAF(String inputPath, String outputDir, QualityLevel quality, VideoInfo videoInfo)
            throws IOException, InterruptedException {

        String qualityDir = outputDir + "/" + quality.name;
        Files.createDirectories(Paths.get(qualityDir));

        // Dinamik scale filtresi kullan
        String scaleFilter = calculateScaleFilter(quality, videoInfo);

        String[] cmafCommand = {
                ffmpegPath,
                "-i", inputPath,
                "-vf", scaleFilter,
                "-c:v", "libx264",
                "-c:a", "aac",
                "-preset", "fast",
                "-crf", "23",
                "-b:v", quality.videoBitrate,
                "-maxrate", quality.videoBitrate,
                "-bufsize", calculateBufferSize(quality.videoBitrate),
                "-b:a", quality.audioBitrate,
                "-f", "hls",
                "-hls_time", "4",
                "-hls_playlist_type", "vod",
                "-hls_segment_type", "fmp4",
                "-hls_fmp4_init_filename", "init.mp4",
                "-hls_segment_filename", qualityDir + "/segment_%03d.m4s",
                "-movflags", "+frag_keyframe+empty_moov+default_base_moof",
                qualityDir + "/playlist.m3u8"
        };

        logger.info("Generating {} CMAF segments with dimensions {}x{}",
                quality.name, quality.width, quality.height);
        logger.debug("FFmpeg command: {}", String.join(" ", cmafCommand));
        executeFFmpegCommand(cmafCommand);

        logger.info("Completed {} encoding", quality.name);
    }

    private String calculateBufferSize(String bitrate) {
        // Bitrate'in 2 katı buffer size (örn: 1500k -> 3000k)
        int value = Integer.parseInt(bitrate.replaceAll("[^0-9]", ""));
        return (value * 2) + "k";
    }

    private void generateMasterHLSManifest(String outputDir, List<QualityLevel> qualities, VideoInfo videoInfo)
            throws IOException {

        StringBuilder masterPlaylist = new StringBuilder();
        masterPlaylist.append("#EXTM3U\n");
        masterPlaylist.append("#EXT-X-VERSION:7\n");

        // Her kalite için stream bilgisi
        for (QualityLevel quality : qualities) {
            int bandwidth = parseBandwidth(quality.videoBitrate) + parseBandwidth(quality.audioBitrate);

            masterPlaylist.append("#EXT-X-STREAM-INF:")
                    .append("BANDWIDTH=").append(bandwidth)
                    .append(",RESOLUTION=").append(quality.width).append("x").append(quality.height)
                    .append(",CODECS=\"avc1.4d401f,mp4a.40.2\"")
                    .append(",FRAME-RATE=25.000\n");
            // ✅ DOĞRU: Her kalite için ayrı playlist
            masterPlaylist.append(quality.name).append("/playlist.m3u8\n");
        }

        // Master playlist dosyasını yaz
        Path masterPath = Paths.get(outputDir, "master.m3u8");
        Files.writeString(masterPath, masterPlaylist.toString());

        logger.info("Created master HLS manifest with {} quality levels", qualities.size());
    }

    private void generateMasterDASHManifest(String outputDir, List<QualityLevel> qualities, VideoInfo videoInfo)
            throws IOException {

        String isoDuration = formatDurationToISO(videoInfo.duration);

        StringBuilder manifest = new StringBuilder();
        manifest.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        manifest.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" ");
        manifest.append("type=\"static\" ");
        manifest.append("mediaPresentationDuration=\"").append(isoDuration).append("\" ");
        manifest.append("profiles=\"urn:mpeg:dash:profile:isoff-main:2011\">\n");
        manifest.append("  <Period>\n");

        // Video AdaptationSet
        manifest.append("    <AdaptationSet mimeType=\"video/mp4\" ");
        manifest.append("codecs=\"avc1.4d401f\" ");
        manifest.append("segmentAlignment=\"true\" ");
        manifest.append("startWithSAP=\"1\">\n");

        // Her kalite için Representation
        for (int i = 0; i < qualities.size(); i++) {
            QualityLevel quality = qualities.get(i);
            int bandwidth = parseBandwidth(quality.videoBitrate);

            manifest.append("      <Representation id=\"video").append(i).append("\" ");
            manifest.append("bandwidth=\"").append(bandwidth).append("\" ");
            manifest.append("width=\"").append(quality.width).append("\" ");
            manifest.append("height=\"").append(quality.height).append("\">\n");

            manifest.append("        <SegmentTemplate ");
            manifest.append("timescale=\"1000\" ");
            manifest.append("initialization=\"").append(quality.name).append("/init.mp4\" ");
            manifest.append("media=\"").append(quality.name).append("/segment_$Number%03d$.m4s\" ");
            manifest.append("duration=\"4000\" ");
            manifest.append("startNumber=\"0\"/>\n");

            manifest.append("      </Representation>\n");
        }

        manifest.append("    </AdaptationSet>\n");

        // Audio AdaptationSet (tek kalite)
        QualityLevel firstQuality = qualities.get(0);
        int audioBandwidth = parseBandwidth(firstQuality.audioBitrate);

        manifest.append("    <AdaptationSet mimeType=\"audio/mp4\" ");
        manifest.append("codecs=\"mp4a.40.2\" ");
        manifest.append("segmentAlignment=\"true\" ");
        manifest.append("startWithSAP=\"1\">\n");

        manifest.append("      <Representation id=\"audio0\" ");
        manifest.append("bandwidth=\"").append(audioBandwidth).append("\">\n");

        manifest.append("        <SegmentTemplate ");
        manifest.append("timescale=\"1000\" ");
        manifest.append("initialization=\"").append(firstQuality.name).append("/init.mp4\" ");
        manifest.append("media=\"").append(firstQuality.name).append("/segment_$Number%03d$.m4s\" ");
        manifest.append("duration=\"4000\" ");
        manifest.append("startNumber=\"0\"/>\n");

        manifest.append("      </Representation>\n");
        manifest.append("    </AdaptationSet>\n");

        manifest.append("  </Period>\n");
        manifest.append("</MPD>\n");

        // DASH manifest dosyasını yaz
        Path manifestPath = Paths.get(outputDir, "master.mpd");
        Files.writeString(manifestPath, manifest.toString());

        logger.info("Created master DASH manifest with {} quality levels", qualities.size());
    }

    private int parseBandwidth(String bitrate) {
        // "1500k" -> 1500000
        int value = Integer.parseInt(bitrate.replaceAll("[^0-9]", ""));
        return value * 1000;
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
        logger.info("Executing FFmpeg command: {}", String.join(" ", command));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.debug("FFmpeg: {}", line);
                output.append(line).append("\n");

                // Error detection
                if (line.contains("Error") || line.contains("Invalid") || line.contains("failed")) {
                    logger.warn("FFmpeg potential error: {}", line);
                }
            }
        }

        boolean finished = process.waitFor(60, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            logger.error("FFmpeg command that timed out: {}", String.join(" ", command));
            throw new RuntimeException("FFmpeg process timed out after 60 minutes");
        }

        int exitCode = process.exitValue();
        logger.info("FFmpeg completed with exit code: {}", exitCode);

        if (exitCode != 0) {
            logger.error("FFmpeg failed with exit code: {}", exitCode);
            logger.error("FFmpeg command: {}", String.join(" ", command));
            logger.error("FFmpeg output: {}", output.toString());
            throw new RuntimeException("FFmpeg process failed with exit code: " + exitCode);
        }

        logger.info("FFmpeg completed successfully");
    }

    // Helper classes
    private static class QualityLevel {
        final String name;
        final int width;
        final int height;
        final String videoBitrate;
        final String audioBitrate;

        QualityLevel(String name, int width, int height, String videoBitrate, String audioBitrate) {
            this.name = name;
            this.width = width;
            this.height = height;
            this.videoBitrate = videoBitrate;
            this.audioBitrate = audioBitrate;
        }
    }

    private static class VideoInfo {
        final double duration;
        final int width;
        final int height;

        VideoInfo(double duration, int width, int height) {
            this.duration = duration;
            this.width = width;
            this.height = height;
        }
    }
}


