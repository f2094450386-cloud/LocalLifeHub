package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class UploadFilePolicy {

    private static final Set<String> ALLOWED_EXTENSIONS =
            new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "gif"));

    private UploadFilePolicy() {
    }

    public static String validateExtension(String originalFilename) {
        if (StrUtil.isBlank(originalFilename) || !originalFilename.contains(".")) {
            throw new IllegalArgumentException("文件扩展名不能为空");
        }
        String extension = StrUtil.subAfter(originalFilename, ".", true).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("仅支持 jpg、jpeg、png、gif 图片");
        }
        return extension;
    }

    public static Path resolveBlogImage(Path uploadRoot, String filename) {
        if (StrUtil.isBlank(filename)) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String normalizedName = filename.replace('\\', '/');
        if (normalizedName.startsWith("/imgs/")) {
            normalizedName = normalizedName.substring("/imgs/".length());
        } else if (normalizedName.startsWith("/")) {
            normalizedName = normalizedName.substring(1);
        }
        if (!normalizedName.startsWith("blogs/")) {
            throw new IllegalArgumentException("只能操作博客图片");
        }

        Path normalizedRoot = uploadRoot.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(normalizedName).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("非法文件路径");
        }
        return resolved;
    }

    public static Path resolveOwnedBlogImage(
            Path uploadRoot,
            String filename,
            Long currentUserId,
            boolean admin
    ) {
        Path resolved = resolveBlogImage(uploadRoot, filename);
        Path relative = uploadRoot.toAbsolutePath().normalize().relativize(resolved);
        if (!admin) {
            if (relative.getNameCount() < 3
                    || !"blogs".equals(relative.getName(0).toString())
                    || !String.valueOf(currentUserId).equals(relative.getName(1).toString())) {
                throw new IllegalArgumentException("只能删除自己上传的图片");
            }
        }
        return resolved;
    }
}
