package com.echomind.console.controller.rest;

import com.echomind.console.dto.StorageUploadResponse;
import com.echomind.console.service.StorageApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

/**
 * 对象存储控制器。
 *
 * <p>聊天图片先上传到这里，返回附件引用后再随聊天消息提交。
 * 本地兜底模式下也通过这里代理读取图片，前端不用关心存储后端。</p>
 */
@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageApplicationService storageService;

    /** 上传聊天图片。 */
    @PostMapping("/images")
    public ResponseEntity<StorageUploadResponse> uploadImage(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(storageService.uploadChatImage(file));
    }

    /** 读取本地兜底对象。 */
    @GetMapping("/objects/{*key}")
    public ResponseEntity<Resource> getObject(@PathVariable String key) {
        String normalized = key.startsWith("/") ? key.substring(1) : key;
        return ResponseEntity.ok()
            .contentType(storageService.mediaTypeFor(normalized))
            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
            .body(storageService.loadLocalObject(normalized));
    }
}
