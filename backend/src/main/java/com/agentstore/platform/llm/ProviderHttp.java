package com.agentstore.platform.llm;

import com.agentstore.platform.llm.ProviderCallException.Kind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/** The JSON-over-HTTP part both adapters share. Classifies every outcome; never keeps a body or a cause. */
final class ProviderHttp {

    private final HttpClient client;
    private final Duration readTimeout;
    private final ObjectMapper mapper;

    ProviderHttp(HttpClient client, Duration readTimeout, ObjectMapper mapper) {
        this.client = client;
        this.readTimeout = readTimeout;
        this.mapper = mapper;
    }

    static ProviderHttp create(Duration connectTimeout, Duration readTimeout, ObjectMapper mapper) {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout).build();
        return new ProviderHttp(client, readTimeout, mapper);
    }

    JsonNode post(String url, Map<String, String> headers, JsonNode body) throws ProviderCallException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(readTimeout)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        headers.forEach(request::header);
        HttpResponse<byte[]> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new ProviderCallException(Kind.TIMEOUT, 0, null);
        } catch (IOException e) {
            throw new ProviderCallException(Kind.IO, 0, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderCallException(Kind.IO, 0, null);
        }
        int status = response.statusCode();
        if (status == 429) {
            throw new ProviderCallException(Kind.RATE_LIMITED, status, retryAfter(response));
        }
        if (status >= 500) {
            throw new ProviderCallException(Kind.SERVER_ERROR, status, null);
        }
        if (status >= 400) {
            throw new ProviderCallException(Kind.CLIENT_ERROR, status, null);
        }
        try {
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new ProviderCallException(Kind.BAD_RESPONSE, status, null);
        }
    }

    private static Duration retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("retry-after").map(value -> {
            try {
                long seconds = Long.parseLong(value.trim());
                return seconds < 0 ? null : Duration.ofSeconds(seconds);
            } catch (NumberFormatException e) {
                return null;   // an HTTP-date form is ignored; the configured backoff applies
            }
        }).orElse(null);
    }
}
