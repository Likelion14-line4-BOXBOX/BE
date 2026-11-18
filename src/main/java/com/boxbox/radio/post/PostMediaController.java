package com.boxbox.radio.post;

import com.boxbox.common.api.ApiException;
import com.boxbox.common.api.ApiResponse;
import com.boxbox.radio.board.BoardViewerResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
class PostMediaController {
    private final BoardViewerResolver viewerResolver;
    private final PostMediaService service;

    @PostMapping(path = "/api/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ApiResponse<PostMediaService.Uploaded>> upload(
            @RequestParam("file") List<MultipartFile> files, @RequestParam("purpose") String purpose,
            HttpServletRequest request) {
        String loginId = viewerResolver.resolveLoginId(request);
        if (loginId == null) {
            throw ApiException.unauthorized();
        }
        if (files.size() != 1) {
            throw ApiException.invalidRequest("이미지 파일을 한 장만 첨부해 주세요.");
        }
        var uploaded = service.upload(loginId, purpose, files.getFirst());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(uploaded));
    }

    @GetMapping("/api/media/post-images/{filename}")
    ResponseEntity<Resource> image(@PathVariable String filename) {
        var image = service.image(filename);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "no-store")
                .body(image.resource());
    }
}
