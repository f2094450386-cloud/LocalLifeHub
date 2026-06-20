package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.security.AdminAccessPolicy;
import com.hmdp.utils.UploadFilePolicy;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    private final Path uploadRoot;
    private final long maxImageBytes;
    private final long maxImagePixels;
    private final AdminAccessPolicy adminAccessPolicy;

    public UploadController(
            @Value("${app.upload.image-dir:src/main/resources/nginx-1.18.0/html/hmdp/imgs}") String imageDir,
            @Value("${app.upload.max-image-bytes:5242880}") long maxImageBytes,
            @Value("${app.upload.max-image-pixels:40000000}") long maxImagePixels,
            AdminAccessPolicy adminAccessPolicy
    ) {
        this.uploadRoot = Paths.get(imageDir).toAbsolutePath().normalize();
        this.maxImageBytes = maxImageBytes;
        this.maxImagePixels = maxImagePixels;
        this.adminAccessPolicy = adminAccessPolicy;
    }

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        if (image == null || image.isEmpty()) {
            return Result.fail("图片不能为空");
        }
        if (image.getSize() > maxImageBytes) {
            return Result.fail("图片大小不能超过 " + maxImageBytes + " 字节");
        }
        try {
            BufferedImage decodedImage = ImageIO.read(image.getInputStream());
            if (decodedImage == null) {
                return Result.fail("上传内容不是有效图片");
            }
            long pixels = (long) decodedImage.getWidth() * decodedImage.getHeight();
            if (pixels <= 0 || pixels > maxImagePixels) {
                return Result.fail("图片尺寸过大");
            }
            String originalFilename = image.getOriginalFilename();
            String suffix = UploadFilePolicy.validateExtension(originalFilename);
            String fileName = createNewFileName(suffix);
            Path target = UploadFilePolicy.resolveBlogImage(uploadRoot, fileName);
            Files.createDirectories(target.getParent());
            image.transferTo(target);
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @DeleteMapping("/blog")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        UserDTO user = UserHolder.getUser();
        try {
            Path target = UploadFilePolicy.resolveOwnedBlogImage(
                    uploadRoot,
                    filename,
                    user.getId(),
                    adminAccessPolicy.isAdmin(user.getId())
            );
            if (Files.isDirectory(target)) {
                return Result.fail("错误的文件名称");
            }
            Files.deleteIfExists(target);
            return Result.ok();
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IOException e) {
            throw new RuntimeException("文件删除失败", e);
        }
    }

    private String createNewFileName(String suffix) {
        Long userId = UserHolder.getUser().getId();
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        return String.format("/blogs/%d/%d/%d/%s.%s", userId, d1, d2, name, suffix);
    }
}
