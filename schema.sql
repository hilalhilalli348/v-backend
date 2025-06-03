-- PostgreSQL Database Schema for Video Streaming Service

-- Create database (run this separately)
-- CREATE DATABASE videostreaming;

-- Connect to the database and create table
-- \c videostreaming;

CREATE TABLE IF NOT EXISTS videos (
                                      id BIGSERIAL PRIMARY KEY,
                                      title VARCHAR(255) NOT NULL,
    filename VARCHAR(255) NOT NULL UNIQUE,
    original_filename VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL DEFAULT 0,
    mime_type VARCHAR(100),
    cmaf_path TEXT,
    hls_manifest_path TEXT,
    dash_manifest_path TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'UPLOADING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    duration INTEGER,
    resolution VARCHAR(20)
    );

-- Create indexes for better performance
CREATE INDEX IF NOT EXISTS idx_videos_status ON videos(status);
CREATE INDEX IF NOT EXISTS idx_videos_created_at ON videos(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_videos_filename ON videos(filename);

-- Create trigger to automatically update updated_at
CREATE OR REPLACE FUNCTION trigger_set_timestamp()
RETURNS TRIGGER AS $
BEGIN
  NEW.updated_at = NOW();
RETURN NEW;
END;
$ LANGUAGE plpgsql;

-- Create trigger for videos table
CREATE TRIGGER set_timestamp
    BEFORE UPDATE ON videos
    FOR EACH ROW
    EXECUTE PROCEDURE trigger_set_timestamp();

-- Insert some sample data (optional)
-- INSERT INTO videos (title, filename, original_filename, file_size, mime_type, status) 
-- VALUES ('Sample Video', 'sample-uuid.mp4', 'sample.mp4', 1024000, 'video/mp4', 'READY');

-- Grant permissions (adjust username as needed)
-- GRANT ALL PRIVILEGES ON DATABASE videostreaming TO postgres;
-- GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO postgres;
-- GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO postgres;