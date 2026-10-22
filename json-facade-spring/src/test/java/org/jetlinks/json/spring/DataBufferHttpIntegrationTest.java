package org.jetlinks.json.spring;

import io.netty.buffer.ByteBuf;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonWriteOptions;
import org.jetlinks.json.spi.JsonProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.LoopResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DataBufferHttpIntegrationTest {
    static Stream<JsonProvider> providers() {
        return DataBufferJsonStreamMapperTest.providers();
    }

    @ParameterizedTest
    @MethodSource("providers")
    void springRequestBodyAndResponseWriteWithTransferReferencesAcrossAsyncDemand(JsonProvider provider) {
        var mapper = DataBufferJsonStreamMapperTest.mapper(provider);
        LoopResources loops = LoopResources.create("databuffer-http", 1, true);
        var consumer = Schedulers.newSingle("databuffer-consumer");
        List<ByteBuf> received = new CopyOnWriteArrayList<>();
        List<ByteBuf> sent = new CopyOnWriteArrayList<>();
        DisposableServer server = null;
        try {
            var handler = new ReactorHttpHandlerAdapter((request, response) -> {
                var streams = DataBufferJsonStreamMapper.of(mapper, response.bufferFactory());
                var values = streams.read(request.getBody().doOnNext(buffer ->
                                received.add(((NettyDataBuffer) buffer).getNativeBuffer())),
                        JsonType.of(Integer.class), JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS));
                return response.writeWith(streams.write(values.publishOn(consumer, 1).map(value -> value + 1),
                        new JsonWriteOptions(JsonWriteOptions.Format.ARRAY, 7, 1024)).doOnNext(buffer ->
                        sent.add(((NettyDataBuffer) buffer).getNativeBuffer())));
            });
            server = HttpServer.create().host("127.0.0.1").port(0).runOn(loops, false)
                    .handle(handler).bindNow(Duration.ofSeconds(10));
            String body = IntStream.range(0, 1000).mapToObj(Integer::toString).collect(Collectors.joining(",", "[", "]"));
            String output = HttpClient.create().keepAlive(false).runOn(loops, false)
                    .post().uri("http://127.0.0.1:" + server.port())
                    .send((request, outbound) -> outbound.sendString(Flux.just(body)))
                    .responseContent().aggregate().asString().block(Duration.ofSeconds(20));
            assertEquals(IntStream.rangeClosed(1, 1000).boxed().toList(),
                    mapper.read(output, new JsonType<List<Integer>>() { }));
        } finally {
            if (server != null) {
                server.disposeNow(Duration.ofSeconds(5));
            }
            loops.disposeLater().block(Duration.ofSeconds(5));
            consumer.dispose();
        }
        assertFalse(received.isEmpty());
        assertFalse(sent.isEmpty());
        received.forEach(buffer -> assertEquals(0, buffer.refCnt()));
        sent.forEach(buffer -> assertEquals(0, buffer.refCnt()));
    }
}
