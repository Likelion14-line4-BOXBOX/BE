package com.boxbox.radio.post;

import com.boxbox.common.api.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

record PostImage(byte[] bytes, String extension, String contentType, int width, int height) {
    private static final int MAX_BYTES = 5_000_000;
    private static final int MAX_DIMENSION = 4096;

    static PostImage inspect(MultipartFile file) {
        if (file.getSize() > MAX_BYTES) {
            throw tooLarge();
        }
        try {
            byte[] bytes = file.getBytes();
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) {
                    throw unsupported();
                }
                var reader = readers.next();
                try {
                    String extension = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                        case "jpeg" -> "jpg";
                        case "png" -> "png";
                        case "webp" -> "webp";
                        default -> throw unsupported();
                    };
                    reader.setInput(input, true, true);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
                        throw tooLarge();
                    }
                    if (width < 1 || height < 1) {
                        throw unsupported();
                    }
                    reader.read(0);
                    String contentType = "image/" + ("jpg".equals(extension) ? "jpeg" : extension);
                    return new PostImage(bytes, extension, contentType, width, height);
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException exception) {
            throw unsupported();
        }
    }

    static ApiException tooLarge() {
        return new ApiException(HttpStatus.valueOf(413), "FILE_TOO_LARGE",
                "이미지는 최대 5MB 및 4096×4096까지 업로드할 수 있습니다.");
    }

    private static ApiException unsupported() {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "읽을 수 있는 JPEG, PNG, WebP 이미지를 첨부해 주세요.");
    }
}
