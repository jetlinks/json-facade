package org.jetlinks.json.fastjson2;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicInteger;
import com.alibaba.fastjson2.JSONWriter;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonOperationOptions;

/** 独立受限堆进程验证原生引用任务不跨同步操作保留，不通过流式入口收集输入。 */
public final class Fastjson2Stress {
    private Fastjson2Stress() {
    }

    public static void main(String[] args) {
        int records = Integer.parseInt(args[0]);
        JsonMapper base = Json.builder().provider("fastjson2").options(Fastjson2Provider.nativeOptions())
                .configureNative(Fastjson2Builder.class,
                        b -> b.writer().config(JSONWriter.Feature.ReferenceDetection)).build();
        JsonMapper operation = base.withOptions(JsonOperationOptions.builder().pretty(true).omitNulls(true).build());
        AtomicInteger closes = new AtomicInteger();
        long bytes = 0;
        for (int i = 0; i < records; i++) {
            JsonMapper json = (i & 1) == 0 ? base : operation;
            Node value = new Node();
            value.id = Integer.toString(i);
            value.next = value;
            byte[] encoded = json.writeBytes(value);
            Node read = json.read(encoded, Node.class);
            verify(read, value.id);
            if (i % 1000 == 0) {
                verify(json.convert(value, Node.class), value.id);
                verify(json.read(new ByteArrayInputStream(encoded) {
                    @Override
                    public void close() {
                        closes.incrementAndGet();
                    }
                }, Node.class), value.id);
            }
            bytes += encoded.length;
        }
        if (closes.get() != 0) {
            throw new AssertionError("Caller input closed");
        }
        System.out.println("{\"provider\":\"fastjson2\",\"records\":" + records + ",\"bytes\":" + bytes
                + ",\"callerCloses\":" + closes.get() + "}");
    }

    private static void verify(Node value, String expected) {
        if (!expected.equals(value.id) || value.next != value) {
            throw new AssertionError("Identity or binding mismatch");
        }
    }

    public static final class Node {
        public String id;
        public Node next;
    }
}
