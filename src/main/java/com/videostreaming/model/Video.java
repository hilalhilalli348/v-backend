package com.videostreaming.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.LocalDateTime;

@Table("videos")
public class Video {

    @Id
    private Long id;

    private String title;
    private String filename;
    private String originalFilename;
    private long fileSize;
    private String mimeType;
    private String cmafPath;
    private String hlsManifestPath;
    private String dashManifestPath;
    private String status; // UPLOADING, PROCESSING, READY, ERROR
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer duration; // video duration in seconds
    private String resolution;

    public Video() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.status = "UPLOADING";
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getCmafPath() {
        return cmafPath;
    }

    public void setCmafPath(String cmafPath) {
        this.cmafPath = cmafPath;
    }

    public String getHlsManifestPath() {
        return hlsManifestPath;
    }

    public void setHlsManifestPath(String hlsManifestPath) {
        this.hlsManifestPath = hlsManifestPath;
    }

    public String getDashManifestPath() {
        return dashManifestPath;
    }

    public void setDashManifestPath(String dashManifestPath) {
        this.dashManifestPath = dashManifestPath;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Integer getDuration() {
        return duration;
    }

    public void setDuration(Integer duration) {
        this.duration = duration;
    }

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }
}