package com.example.similarproducts.infrastructure;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** Loopback-only HTTP fixture. Blocking bind/dispose are restricted to test lifecycle code. */
public final class LocalHttpStub implements AutoCloseable {

    public record Reply(int status, String contentType, String body) {
        public static Reply json(String body) {
            return new Reply(200, "application/json", body);
        }
    }

    private final ConcurrentLinkedQueue<String> requests = new ConcurrentLinkedQueue<>();
    private final DisposableServer server;

    public LocalHttpStub(Function<String, Reply> dispatcher) {
        server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
            requests.add(request.uri());
            Reply reply = dispatcher.apply(request.uri());
            response.status(reply.status());
            if (reply.contentType() != null) {
                response.header("Content-Type", reply.contentType());
            }
            return response.sendString(Mono.just(reply.body()));
        }).bindNow();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.port();
    }

    public List<String> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.disposeNow();
    }
}
