package com.hmdp.utils;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadFilePolicyTest {

    private final Path uploadRoot = Paths.get("build", "uploads").toAbsolutePath().normalize();

    @Test
    void shouldResolveGeneratedBlogPathInsideUploadRoot() {
        Path resolved = UploadFilePolicy.resolveBlogImage(uploadRoot, "/imgs/blogs/a/b/test.jpg");

        assertThat(resolved).isEqualTo(uploadRoot.resolve("blogs/a/b/test.jpg"));
    }

    @Test
    void shouldRejectPathTraversal() {
        assertThatThrownBy(() ->
                UploadFilePolicy.resolveBlogImage(uploadRoot, "/imgs/blogs/../../application.yaml"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonBlogPath() {
        assertThatThrownBy(() ->
                UploadFilePolicy.resolveBlogImage(uploadRoot, "/imgs/icons/user.png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectUnsupportedExtension() {
        assertThatThrownBy(() -> UploadFilePolicy.validateExtension("payload.jsp"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
