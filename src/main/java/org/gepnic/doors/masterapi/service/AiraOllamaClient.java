package org.gepnic.doors.masterapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gepnic.doors.masterapi.config.AiraProperties;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;

/** Only transport for chat, embeddings and health. Destination/model settings are startup snapshots. */
final class AiraOllamaClient {
    private final URI origin;
    private final String model;
    private final String embeddingModel;
    private final Duration timeout;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Semaphore inFlight = new Semaphore(2);
    private enum Endpoint {
        CHAT("/api/chat"), EMBEDDINGS("/api/embeddings"), TAGS("/api/tags");
        final String path; Endpoint(String path) { this.path = path; }
    }

    AiraOllamaClient(AiraProperties properties, ObjectMapper mapper) {
        this.origin = validateOrigin(properties.getBaseUrl());
        this.model = validModel(properties.getModel());
        this.embeddingModel = validModel(properties.getEmbeddingModel());
        this.timeout = Duration.ofSeconds(Math.max(1, Math.min(180, properties.getTimeoutSeconds())));
        this.mapper = mapper.copy();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector() {
                    public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    public void connectFailed(URI uri, SocketAddress address, IOException ex) {}
                }).build();
    }

    static URI validateOrigin(String configured) {
        try {
            URI uri = URI.create(configured);
            String host = uri.getHost();
            if (!Set.of("http", "https").contains(uri.getScheme()) || host == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
                    || uri.getPort() < 1 || uri.getPort() > 65535
                    || !host.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) {
                throw new IllegalArgumentException();
            }
            int[] ip = Arrays.stream(host.split("\\.")).mapToInt(Integer::parseInt).toArray();
            if (Arrays.stream(ip).anyMatch(n -> n > 255)
                    || !(ip[0] == 10 || ip[0] == 127 || (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31)
                    || (ip[0] == 192 && ip[1] == 168))) throw new IllegalArgumentException();
            return new URI(uri.getScheme(), null, host, uri.getPort(), null, null, null);
        } catch (Exception ex) {
            throw new IllegalArgumentException("AIra requires an explicit private/loopback IPv4 origin and port, without path or credentials");
        }
    }

    private static String validModel(String model) {
        if (model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"))
            throw new IllegalArgumentException("Invalid configured AIra model");
        return model;
    }

    String model() { return model; }
    String embeddingModel() { return embeddingModel; }

    Map<String, Object> chatPayload(String question, List<String> references, String trustedGuidance) {
        return Map.of("model", model, "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", AiraPromptBoundary.SYSTEM
                                + (model.startsWith("qwen3") ? "\n/no_think\n" : "")
                                + trustedGuidance),
                        Map.of("role", "user", "content", AiraPromptBoundary.envelope(mapper, question, references))),
                "options", Map.of("temperature", 0.0, "num_predict", 384, "num_ctx", 8192));
    }

    String chat(String question, List<String> references, String trustedGuidance) {
        JsonNode reply = send(Endpoint.CHAT, chatPayload(question, references, trustedGuidance));
        JsonNode message = reply.path("message");
        if (!reply.path("done").asBoolean() || !"assistant".equals(message.path("role").asText())
                || !message.path("content").isTextual()
                || !message.path("tool_calls").isMissingNode() && !message.path("tool_calls").isEmpty())
            throw new IllegalStateException("Invalid AIra response envelope");
        String answer = message.path("content").asText()
                .replaceAll("(?is)<think>.*?(?:</think>|\\z)", "");
        if (answer.length() > 16_000) throw new IllegalStateException("AIra answer exceeds limit");
        return AiraPromptBoundary.clean(answer, 16_000);
    }

    float[] embed(String input) {
        String text = AiraPromptBoundary.clean(input, 64_000);
        if (text.isBlank()) throw new IllegalArgumentException("Empty AIra embedding input");
        JsonNode vector = send(Endpoint.EMBEDDINGS, Map.of("model", embeddingModel, "prompt", text)).path("embedding");
        if (!vector.isArray() || vector.isEmpty() || vector.size() > 16_384)
            throw new IllegalStateException("Invalid AIra embedding");
        float[] result = new float[vector.size()];
        for (int i = 0; i < result.length; i++) {
            if (!vector.get(i).isNumber()) throw new IllegalStateException("Invalid AIra embedding value");
            result[i] = vector.get(i).floatValue();
            if (!Float.isFinite(result[i])) throw new IllegalStateException("Invalid AIra embedding value");
        }
        return result;
    }

    boolean available() {
        for (JsonNode value : send(Endpoint.TAGS, null).path("models")) {
            String name = value.path("name").asText();
            if (name.equals(model) || name.startsWith(model + ":")) return true;
        }
        return false;
    }

    private JsonNode send(Endpoint endpoint, Map<String, Object> body) {
        if (!inFlight.tryAcquire()) throw new IllegalStateException("AIra capacity is busy");
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(origin.resolve(endpoint.path))
                    .timeout(endpoint == Endpoint.TAGS ? Duration.ofSeconds(5) : timeout)
                    .header("Accept", "application/json");
            if (body == null) builder.GET();
            else {
                byte[] json = mapper.writeValueAsBytes(body);
                if (json.length > 300_000) throw new IllegalArgumentException("AIra request exceeds limit");
                builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json));
            }
            HttpResponse<byte[]> response = http.send(builder.build(), info -> new LimitedBody(1_048_576));
            if (response.statusCode() != 200) throw new IllegalStateException("AIra upstream request rejected");
            JsonNode result = mapper.readTree(response.body());
            if (result == null || !result.isObject() || result.has("error"))
                throw new IllegalStateException("Invalid AIra upstream JSON");
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("AIra request interrupted");
        } catch (IOException ex) {
            // Do not expose upstream bodies, input text or network diagnostics to callers/logs.
            throw new IllegalStateException("AIra upstream unavailable");
        } finally {
            inFlight.release();
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IOException("AIra response exceeds limit")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
