package com.videostreaming.service;


import com.videostreaming.model.Video;
import com.videostreaming.repository.VideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

@Service
public class VideoService {

    private static final Logger logger = LoggerFactory.getLogger(VideoService.class);

    @Value("${video.storage.path}")
    private String videoStoragePath;

    private final VideoRepository videoRepository;
    private final VideoProcessingService videoProcessingService;

    public VideoService(VideoRepository videoRepository, VideoProcessingService videoProcessingService) {
        this.videoRepository = videoRepository;
        this.videoProcessingService = videoProcessingService;
    }

    public Mono<Video> uploadVideo(String title, Mono<FilePart> filePartMono) {
        return filePartMono.flatMap(filePart -> {

            // Generate unique filename
            String originalFilename = filePart.filename();
            String extension = getFileExtension(originalFilename);
            String filename = UUID.randomUUID().toString() + extension;

            // Create video entity
            Video video = new Video();
            video.setTitle(title);
            video.setFilename(filename);
            video.setOriginalFilename(originalFilename);
            video.setMimeType(getContentType(extension));

            // Save to database first
            return videoRepository.save(video)
                    .flatMap(savedVideo -> {
                        // Then save file
                        return saveFile(filePart, filename)
                                .flatMap(savedFile -> {
                                    // Update file size
                                    savedVideo.setFileSize(savedFile.length());
                                    return videoRepository.save(savedVideo);
                                })
                                .flatMap(updatedVideo -> {
                                    // Start processing asynchronously
                                    videoProcessingService.processVideo(updatedVideo).subscribe();
                                    return Mono.just(updatedVideo);
                                });
                    });
        });
    }

    private Mono<File> saveFile(FilePart filePart, String filename) {
        return Mono.fromCallable(() -> {
            try {
                // Create storage directory if it doesn't exist
                Path storagePath = Paths.get(videoStoragePath);
                Files.createDirectories(storagePath);

                Path filePath = storagePath.resolve(filename);
                File file = filePath.toFile();

                return file;
            } catch (IOException e) {
                throw new RuntimeException("Failed to create file", e);
            }
        }).flatMap(file -> {
            // Write file content
            return filePart.content()
                    .reduce(file, (f, dataBuffer) -> {
                        try {
                            byte[] bytes = new byte[dataBuffer.readableByteCount()];
                            dataBuffer.read(bytes);
                            Files.write(f.toPath(), bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                            return f;
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to write file", e);
                        }
                    });
        });
    }

    public Mono<Video> getVideoById(Long id) {
        return videoRepository.findById(id);
    }

    public Flux<Video> getAllVideos() {
        return videoRepository.findAllByOrderByCreatedAtDesc();
    }

    public Mono<Video> getVideoByFilename(String filename) {
        return videoRepository.findByFilename(filename);
    }

    public Mono<Void> deleteVideo(Long id) {
        return videoRepository.findById(id)
                .flatMap(video -> {
                    // Delete files
                    deleteVideoFiles(video);
                    // Delete from database
                    return videoRepository.delete(video);
                });
    }

    private void deleteVideoFiles(Video video) {
        try {
            // Delete original file
            Path originalFile = Paths.get(videoStoragePath, video.getFilename());
            Files.deleteIfExists(originalFile);

            // Delete processed files
            if (video.getCmafPath() != null) {
                Path processedDir = Paths.get(video.getCmafPath());
                if (Files.exists(processedDir)) {
                    Files.walk(processedDir)
                            .map(Path::toFile)
                            .forEach(File::delete);
                }
            }
        } catch (IOException e) {
            logger.error("Error deleting video files for: {}", video.getFilename(), e);
        }
    }

    private String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "";
        }
        int lastDotIndex = filename.lastIndexOf('.');
        return lastDotIndex == -1 ? "" : filename.substring(lastDotIndex);
    }

    private String getContentType(String extension) {
        switch (extension.toLowerCase()) {
            case ".mp4":
                return "video/mp4";
            case ".avi":
                return "video/x-msvideo";
            case ".mov":
                return "video/quicktime";
            case ".wmv":
                return "video/x-ms-wmv";
            case ".flv":
                return "video/x-flv";
            case ".webm":
                return "video/webm";
            case ".mkv":
                return "video/x-matroska";
            default:
                return "video/mp4";
        }
    }
}