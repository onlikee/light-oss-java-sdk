package com.onlikee.lightoss;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class TestHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentLinkedQueue<Response> responses = new ConcurrentLinkedQueue<>();
    private final List<Request> requests = java.util.Collections.synchronizedList(new ArrayList<>());

    TestHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    void json(int status, String dataJson) {
        response(status, ("{\"request_id\":\"$REQUEST_ID$\",\"data\":" + dataJson + "}")
                .getBytes(StandardCharsets.UTF_8), Map.of("Content-Type", "application/json"));
    }

    void error(int status, String code, String message) {
        response(status, ("{\"request_id\":\"$REQUEST_ID$\",\"error\":{\"code\":\""
                + code + "\",\"message\":\"" + message + "\"}}")
                .getBytes(StandardCharsets.UTF_8), Map.of("Content-Type", "application/json"));
    }

    void response(int status, byte[] body, Map<String, String> headers) {
        responses.add(new Response(status, body.clone(), Map.copyOf(headers), 0));
    }

    void delayedResponse(int status, byte[] body, long delayMillis) {
        responses.add(new Response(status, body.clone(), Map.of("Content-Type", "application/json"), delayMillis));
    }

    Request lastRequest() {
        synchronized (requests) {
            return requests.get(requests.size() - 1);
        }
    }

    List<Request> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        requests.add(new Request(
                exchange.getRequestMethod(),
                exchange.getRequestURI(),
                exchange.getRequestHeaders().entrySet().stream()
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey, entry -> List.copyOf(entry.getValue()))),
                requestBody));
        Response response = responses.poll();
        if (response == null) {
            response = new Response(500, new byte[0], Map.of(), 0);
        }
        if (response.delayMillis() > 0) {
            try {
                Thread.sleep(response.delayMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        String requestId = exchange.getRequestHeaders().getFirst("X-Request-ID");
        exchange.getResponseHeaders().set("X-Request-ID", requestId == null ? "server-request" : requestId);
        response.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        byte[] body = response.body();
        String responseText = new String(body, StandardCharsets.UTF_8);
        if (responseText.contains("$REQUEST_ID$")) {
            body = responseText.replace("$REQUEST_ID$", requestId == null ? "server-request" : requestId)
                    .getBytes(StandardCharsets.UTF_8);
        }
        if (exchange.getRequestMethod().equals("HEAD") || response.status() == 204) {
            exchange.sendResponseHeaders(response.status(), -1);
        } else {
            exchange.sendResponseHeaders(response.status(), body.length);
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    record Request(String method, URI uri, Map<String, List<String>> headers, byte[] body) {
        String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst()
                    .orElse(null);
        }

        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private record Response(int status, byte[] body, Map<String, String> headers, long delayMillis) {
    }
}
