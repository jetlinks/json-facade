package org.jetlinks.json.netty;

import io.netty.buffer.ByteBuf;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonWriteOptions;
import org.jetlinks.json.spi.JsonProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.netty.Connection;
import reactor.netty.DisposableServer;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.TcpClient;
import reactor.netty.tcp.TcpServer;
import reactor.test.StepVerifier;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactorNettyIntegrationTest {
    static Stream<JsonProvider> providers() {
        return NettyJsonStreamMapperTest.providers();
    }

    @ParameterizedTest
    @MethodSource("providers")
    void receiveAndSendKeepOwnedBuffersAliveAcrossAsyncDemand(JsonProvider provider) {
        JsonMapper mapper = NettyJsonStreamMapperTest.mapper(provider);
        LoopResources loops = LoopResources.create("json-netty-test", 1, true);
        List<ByteBuf> received = new CopyOnWriteArrayList<>();
        List<ByteBuf> sent = new CopyOnWriteArrayList<>();
        reactor.core.scheduler.Scheduler consumer = reactor.core.scheduler.Schedulers.newSingle("json-consumer");
        DisposableServer server = null;
        Connection client = null;
        try {
            server = TcpServer.create().host("127.0.0.1").port(0).runOn(loops, false)
                    .handle((inbound, outbound) -> {
                        NettyJsonStreamMapper streams = NettyJsonStreamMapper.of(mapper, outbound.alloc());
                        Flux<Integer> values = streams.read(inbound.receive().doOnNext(received::add),
                                JsonType.of(Integer.class), JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS))
                                .take(1000).publishOn(consumer, 1);
                        return outbound.send(streams.write(values.map(value -> value + 1),
                                new JsonWriteOptions(JsonWriteOptions.Format.ARRAY, 7, 1024)).doOnNext(sent::add));
                    }).bindNow(Duration.ofSeconds(10));
            client = TcpClient.create().host("127.0.0.1").port(server.port()).runOn(loops, false)
                    .connectNow(Duration.ofSeconds(10));
            Connection connection = client;
            NettyJsonStreamMapper streams = NettyJsonStreamMapper.of(mapper, connection.outbound().alloc());
            // The fixture has 1000 requests; the server's completed send closes the response source.
            var sending = connection.outbound().send(streams.write(Flux.range(0, 1000),
                            new JsonWriteOptions(JsonWriteOptions.Format.ARRAY, 17, 1024)))
                    .then();
            Flux<Integer> response = streams.read(connection.inbound().receive(), JsonType.of(Integer.class),
                    JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS));
            StepVerifier.create(Flux.merge(sending.thenMany(Flux.<Integer>empty()), response), 0)
                    .thenRequest(1).expectNext(1).thenRequest(999)
                    .expectNextSequence(java.util.stream.IntStream.rangeClosed(2, 1000).boxed().toList())
                    .thenRequest(1).expectComplete().verify(Duration.ofSeconds(20));
        } finally {
            if (client != null) {
                client.disposeNow(Duration.ofSeconds(5));
            }
            if (server != null) {
                server.disposeNow(Duration.ofSeconds(5));
            }
            loops.disposeLater().block(Duration.ofSeconds(5));
            consumer.dispose();
        }
        assertTrue(!received.isEmpty() && !sent.isEmpty());
        received.forEach(buffer -> assertEquals(0, buffer.refCnt()));
        sent.forEach(buffer -> assertEquals(0, buffer.refCnt()));
    }
}
