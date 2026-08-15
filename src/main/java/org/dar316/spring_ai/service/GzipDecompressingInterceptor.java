package org.dar316.spring_ai.service;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

/**
 * Transparently decompresses HTTP responses encoded only with gzip.
 */
public final class GzipDecompressingInterceptor
        implements ClientHttpRequestInterceptor {

    @Override
    public @NonNull ClientHttpResponse intercept(
            @NonNull HttpRequest request,
            byte @NonNull [] body,
            ClientHttpRequestExecution execution
    ) throws IOException {
        ClientHttpResponse response =
                execution.execute(request, body);

        if (!hasOnlyGzipEncoding(response.getHeaders())) {
            return response;
        }

        return new GunzippedClientHttpResponse(response);
    }

    private static boolean hasOnlyGzipEncoding(
            HttpHeaders headers
    ) {
        List<String> rawValues = headers.get(
                HttpHeaders.CONTENT_ENCODING
        );

        if (rawValues == null || rawValues.isEmpty()) {
            return false;
        }

        List<String> encodings = rawValues.stream()
                .filter(Objects::nonNull)
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();

        return encodings.size() == 1
                && encodings.getFirst().equalsIgnoreCase("gzip");
    }

    private static final class GunzippedClientHttpResponse
            implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final HttpHeaders headers;

        private InputStream decompressedBody;

        private GunzippedClientHttpResponse(
                ClientHttpResponse delegate
        ) {
            this.delegate = delegate;
            this.headers = createDecompressedHeaders(delegate);
        }

        @Override
        public @NonNull HttpStatusCode getStatusCode()
                throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public @NonNull String getStatusText()
                throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public synchronized @NonNull InputStream getBody()
                throws IOException {
            if (decompressedBody == null) {
                decompressedBody = new GZIPInputStream(
                        delegate.getBody()
                );
            }

            return decompressedBody;
        }

        @Override
        public @NonNull HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public void close() {
            /*
             * Closing the delegate closes the underlying network body.
             */
            delegate.close();
        }

        private static HttpHeaders createDecompressedHeaders(
                ClientHttpResponse delegate
        ) {
            HttpHeaders decompressedHeaders = new HttpHeaders();
            decompressedHeaders.putAll(delegate.getHeaders());

            /*
             * Content-Length belongs to the compressed representation.
             * After decompression the length may be unknown.
             */
            decompressedHeaders.remove(
                    HttpHeaders.CONTENT_ENCODING
            );
            decompressedHeaders.remove(
                    HttpHeaders.CONTENT_LENGTH
            );

            return decompressedHeaders;
        }
    }
}