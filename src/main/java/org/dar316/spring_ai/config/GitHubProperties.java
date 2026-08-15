package org.dar316.spring_ai.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "github")
public final class GitHubProperties {

    @Valid
    private final Api api = new Api();

    @Valid
    private final Index index = new Index();

    public Api getApi() {
        return api;
    }

    public Index getIndex() {
        return index;
    }

    public static final class Api {

        @NotBlank
        private String baseUrl = "https://api.github.com";

        private String token = "";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token ==  null ? "" : token;
        }
    }

    public static final class Index {

        @Min(1)
        private int maxFiles = 500;

        @Min(1)
        private long maxFileSizeBytes = 524_288L;

        @Min(1)
        private long maxTotalBytes = 8_388_608L;

        @Min(1)
        private int maxChunks = 10_000;

        public int getMaxFiles() {
            return maxFiles;
        }

        public void setMaxFiles(int maxFiles) {
            this.maxFiles = maxFiles;
        }

        public long getMaxFileSizeBytes() {
            return maxFileSizeBytes;
        }

        public void setMaxFileSizeBytes(long maxFileSizeBytes) {
            this.maxFileSizeBytes = maxFileSizeBytes;
        }

        public long getMaxTotalBytes() {
            return maxTotalBytes;
        }

        public void setMaxTotalBytes(long maxTotalBytes) {
            this.maxTotalBytes = maxTotalBytes;
        }

        public int getMaxChunks() {
            return maxChunks;
        }

        public void setMaxChunks(int maxChunks) {
            this.maxChunks = maxChunks;
        }
    }
}
