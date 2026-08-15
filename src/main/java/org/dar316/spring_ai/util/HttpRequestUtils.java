package org.dar316.spring_ai.util;

import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.time.Duration;

public final class HttpRequestUtils {

    private HttpRequestUtils() {}

    public static JdkClientHttpRequestFactory wikiTimeout(int seconds) {
        var reqFactory = new JdkClientHttpRequestFactory();
        reqFactory.setReadTimeout(Duration.ofSeconds(seconds));

        return reqFactory;
    }
}
