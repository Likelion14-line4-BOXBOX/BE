package com.boxbox.radio.post;

import com.boxbox.common.api.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
class PostMediaService {
    private final JdbcClient jdbc;
    private final PostMediaUrl urls;
    private final Path directory;

    PostMediaService(JdbcClient jdbc, PostMediaUrl urls,
                     @Value("${boxbox.posts.media-directory}") Path directory) {
        this.jdbc = jdbc;
        this.urls = urls;
        this.directory = directory;
    }

    Uploaded upload(String loginId, String purpose, MultipartFile file) {
        long owner = jdbc.sql("SELECT id FROM users WHERE login_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL")
                .param(loginId).query(Long.class).optional().orElseThrow(ApiException::unauthorized);
        if (!"POST_IMAGE".equals(purpose)) {
            throw ApiException.invalidRequest("purpose는 POST_IMAGE여야 합니다.");
        }

        PostImage image = PostImage.inspect(file);
        String filename = UUID.randomUUID() + "." + image.extension();
        String key = PostMediaUrl.UPLOAD_PREFIX + filename;
        String url = urls.resolve(key);
        Path path = directory.resolve(filename);
        try {
            Files.createDirectories(directory);
            Files.write(path, image.bytes(), StandardOpenOption.CREATE_NEW);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_SERVICE_UNAVAILABLE",
                    "이미지를 저장할 수 없습니다.");
        }

        try {
            var generated = new GeneratedKeyHolder();
            jdbc.sql("""
                    INSERT INTO media_files (owner_id, object_key, content_type, size_bytes, width, height)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """).params(owner, key, image.contentType(), image.bytes().length, image.width(), image.height())
                    .update(generated, "id");
            return new Uploaded(generated.getKey().toString(), url, image.width(), image.height(),
                    image.contentType(), image.bytes().length);
        } catch (RuntimeException exception) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    StoredImage image(String filename) {
        if (!filename.matches(PostMediaUrl.UPLOAD_FILENAME)) {
            throw notFound();
        }
        String contentType = jdbc.sql("""
                SELECT content_type FROM media_files
                WHERE object_key = ? AND deleted_at IS NULL
                  AND (status = 'ATTACHED' OR (status = 'UPLOADED'
                       AND (expires_at IS NULL OR expires_at > UTC_TIMESTAMP(6))))
                """).param(PostMediaUrl.UPLOAD_PREFIX + filename).query(String.class).optional()
                .orElseThrow(PostMediaService::notFound);
        Resource resource = new FileSystemResource(directory.resolve(filename));
        if (!resource.isReadable()) {
            throw notFound();
        }
        return new StoredImage(resource, contentType);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND", "이미지를 찾을 수 없습니다.");
    }

    record Uploaded(String mediaId, String url, int width, int height, String contentType, int sizeBytes) {}
    record StoredImage(Resource resource, String contentType) {}
}
