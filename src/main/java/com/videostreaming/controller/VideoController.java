package com.videostreaming.controller;


import com.videostreaming.model.Video;
import com.videostreaming.service.VideoService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api/videos")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class VideoController {

    private final VideoService videoService;

    public VideoController(VideoService videoService) {
        this.videoService = videoService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Video>> uploadVideo(
            @RequestPart("title") String title,
            @RequestPart("file") Mono<FilePart> filePartMono) {

        return videoService.uploadVideo(title, filePartMono)
                .map(video -> ResponseEntity.ok()
                        .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                        .body(video))
                .onErrorReturn(ResponseEntity.badRequest()
                        .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                        .build());
    }

    @GetMapping
    public Flux<Video> getAllVideos() {
        return videoService.getAllVideos();
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<Video>> getVideoById(@PathVariable Long id) {
        return videoService.getVideoById(id)
                .map(video -> ResponseEntity.ok()
                        .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                        .body(video))
                .defaultIfEmpty(ResponseEntity.notFound()
                        .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                        .build());
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteVideo(@PathVariable Long id) {
        return videoService.deleteVideo(id)
                .then(Mono.just(ResponseEntity.ok()
                        .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                        .<Void>build()))
                .onErrorReturn(ResponseEntity.notFound()
                        .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                        .build());
    }

    // HLS Playlist
    @GetMapping("/{id}/hls/playlist.m3u8")
    public Mono<ResponseEntity<?>> getHlsPlaylist(@PathVariable Long id) {
        return videoService.getVideoById(id)
                .map(video -> {
                    if (video.getHlsManifestPath() != null) {
                        Path playlistPath = Paths.get(video.getHlsManifestPath());
                        if (Files.exists(playlistPath)) {
                            Resource resource = new FileSystemResource(playlistPath);
                            return ResponseEntity.ok()
                                    .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
                                    .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                                    .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                                    .body(resource);
                        }
                    }
                    return ResponseEntity.<Resource>notFound()
                            .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                            .build();
                })
                .defaultIfEmpty(ResponseEntity.<Resource>notFound()
                        .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                        .build());
    }


    // Kalite-specific HLS playlist için yeni endpoint
    @GetMapping("/{id}/hls/{quality}/playlist.m3u8")
    public Mono<ResponseEntity<?>> getQualityHlsPlaylist(
            @PathVariable Long id,
            @PathVariable String quality) {

        System.out.println("🔍 Quality HLS Request - ID: " + id + ", Quality: " + quality);

        return videoService.getVideoById(id)
                .map(video -> {
                    System.out.println("📹 Video CMAF Path: " + video.getCmafPath());

                    if (video.getCmafPath() != null) {
                        Path playlistPath = Paths.get(video.getCmafPath(), quality, "playlist.m3u8");
                        System.out.println("🔍 Looking for: " + playlistPath.toAbsolutePath());
                        System.out.println("📄 File exists: " + Files.exists(playlistPath));

                        if (Files.exists(playlistPath)) {
                            Resource resource = new FileSystemResource(playlistPath);
                            return ResponseEntity.ok()
                                    .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
                                    .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                                    .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                                    .body(resource);
                        } else {
                            System.out.println("❌ File not found!");
                        }
                    }
                    return ResponseEntity.<Resource>notFound()
                            .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                            .build();
                })
                .defaultIfEmpty(ResponseEntity.<Resource>notFound()
                        .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                        .build());
    }

    // Kalite-specific HLS segment dosyaları için
    @GetMapping("/{id}/hls/{quality}/{filename:.+}")
    public Mono<ResponseEntity<?>> getQualityHlsFile(
            @PathVariable Long id,
            @PathVariable String quality,
            @PathVariable String filename) {

        System.out.println("🔍 Quality HLS File - ID: " + id + ", Quality: " + quality + ", File: " + filename);

        return videoService.getVideoById(id)
                .map(video -> {
                    if (video.getCmafPath() != null) {
                        Path filePath = Paths.get(video.getCmafPath(), quality, filename);
                        System.out.println("🔍 Looking for: " + filePath.toAbsolutePath());

                        if (Files.exists(filePath)) {
                            Resource resource = new FileSystemResource(filePath);
                            String contentType = determineContentType(filename);

                            return ResponseEntity.ok()
                                    .contentType(MediaType.parseMediaType(contentType))
                                    .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                                    .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                                    .body(resource);
                        }
                    }
                    return ResponseEntity.<Resource>notFound()
                            .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                            .build();
                });
    }


    // DASH Manifest
    @GetMapping("/{id}/dash/manifest.mpd")
    public Mono<ResponseEntity<?>> getDashManifest(@PathVariable Long id) {
        return videoService.getVideoById(id)
                .map(video -> {
                    if (video.getDashManifestPath() != null) {
                        Path manifestPath = Paths.get(video.getDashManifestPath());
                        if (Files.exists(manifestPath)) {
                            Resource resource = new FileSystemResource(manifestPath);
                            return ResponseEntity.ok()
                                    .contentType(MediaType.parseMediaType("application/dash+xml"))
                                    .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                                    .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                                    .body(resource);
                        }
                    }
                    return ResponseEntity.<Resource>notFound()
                            .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                            .build();
                })
                .defaultIfEmpty(ResponseEntity.<Resource>notFound()
                        .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                        .build());
    }


    // Kalite-specific DASH init dosyaları için
    @GetMapping("/{id}/dash/{quality}/init.mp4")
    public Mono<ResponseEntity<?>> getQualityDashInit(
            @PathVariable Long id,
            @PathVariable String quality) {

        return videoService.getVideoById(id)
                .map(video -> {
                    if (video.getCmafPath() != null) {
                        Path initPath = Paths.get(video.getCmafPath(), quality, "init.mp4");
                        System.out.println("🔍 Looking for: " + initPath.toAbsolutePath());

                        if (Files.exists(initPath)) {
                            Resource resource = new FileSystemResource(initPath);

                            return ResponseEntity.ok()
                                    .contentType(MediaType.parseMediaType("video/mp4"))
                                    .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                                    .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                                    .body(resource);
                        }
                    }
                    return ResponseEntity.<Resource>notFound()
                            .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                            .build();
                });
    }

    // Kalite-specific DASH segment dosyaları için
    @GetMapping("/{id}/dash/{quality}/{filename:.+}")
    public Mono<ResponseEntity<?>> getQualityDashFile(
            @PathVariable Long id,
            @PathVariable String quality,
            @PathVariable String filename) {

        System.out.println("🔍 Quality HLS File - ID: " + id + ", Quality: " + quality + ", File: " + filename);

        return videoService.getVideoById(id)
                .map(video -> {
                    if (video.getCmafPath() != null) {
                        Path filePath = Paths.get(video.getCmafPath(), quality, filename);
                        System.out.println("🔍 Looking for: " + filePath.toAbsolutePath());

                        if (Files.exists(filePath)) {
                            Resource resource = new FileSystemResource(filePath);
                            String contentType = determineContentType(filename);

                            return ResponseEntity.ok()
                                    .contentType(MediaType.parseMediaType(contentType))
                                    .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                                    .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                                    .body(resource);
                        }
                    }
                    return ResponseEntity.<Resource>notFound()
                            .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                            .build();
                });
    }


    // DASH Files (init files, segments)


    // Generic file serving (fallback)
    @GetMapping("/{id}/files/{filename:.+}")
    public Mono<ResponseEntity<?>> getFile(
            @PathVariable Long id,
            @PathVariable String filename) {

        return videoService.getVideoById(id)
                .map(video -> {
                    if (video.getCmafPath() != null) {
                        Path filePath = Paths.get(video.getCmafPath(), filename);
                        if (Files.exists(filePath) && filePath.startsWith(Paths.get(video.getCmafPath()))) {
                            Resource resource = new FileSystemResource(filePath);

                            String contentType = determineContentType(filename);

                            return ResponseEntity.ok()
                                    .contentType(MediaType.parseMediaType(contentType))
                                    .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                                    .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                                    .body(resource);
                        }
                    }
                    return ResponseEntity.<Resource>notFound()
                            .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                            .build();
                })
                .defaultIfEmpty(ResponseEntity.<Resource>notFound()
                        .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                        .build());
    }

    // OPTIONS preflight için
    @RequestMapping(method = RequestMethod.OPTIONS)
    public ResponseEntity<Void> handleOptions() {
        return ResponseEntity.ok()
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, DELETE, OPTIONS")
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "*")
                .build();
    }

    private String determineContentType(String filename) {
        if (filename.endsWith(".m4s")) {
            return "video/iso.segment";
        } else if (filename.endsWith(".mp4")) {
            return "video/mp4";
        } else if (filename.endsWith(".m3u8")) {
            return "application/vnd.apple.mpegurl";
        } else if (filename.endsWith(".mpd")) {
            return "application/dash+xml";
        } else {
            return "application/octet-stream";
        }
    }
}