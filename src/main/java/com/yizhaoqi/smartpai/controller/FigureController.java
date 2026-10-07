package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.service.FigureAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/documents/figures")
public class FigureController {
    private final FigureAccessService figures;
    public FigureController(FigureAccessService figures) { this.figures = figures; }

    @GetMapping("/{fileMd5}/{generation}/{pageNumber}/{figureIndex}/image")
    public ResponseEntity<?> image(@PathVariable String fileMd5, @PathVariable long generation,
            @PathVariable int pageNumber, @PathVariable int figureIndex, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken)
            return error(401, "请先登录");
        try {
            var image = figures.read(authentication.getName(), fileMd5, generation, pageNumber, figureIndex);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header("X-Content-Type-Options", "nosniff")
                    .header(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION)
                    .contentType(MediaType.parseMediaType(image.mimeType()))
                    .contentLength(image.bytes().length).body(image.bytes());
        } catch (ResponseStatusException failure) {
            return error(failure.getStatusCode().value(), failure.getReason());
        }
    }

    private ResponseEntity<?> error(int status, String reason) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", status, "message", reason));
    }
}
