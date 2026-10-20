package org.jetlinks.json.tck;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonValue;

/** Standalone bounded-heap proof that retained child/missing views and validation errors do not
 * retain former ancestor storage. Run with -Xmx64m and either real Provider on the classpath.
 */
public final class JsonTreeAccessStress {
    private JsonTreeAccessStress() {
    }

    public static void main(String[] args) {
        JsonMapper mapper = Json.builder().provider(args[0]).build();
        List<JsonValue> leaves = new ArrayList<>();
        List<JsonValue> missing = new ArrayList<>();
        List<JsonException> failures = new ArrayList<>();
        for (int i = 0; i < 512; i++) {
            JsonValue root = mapper.createObject(Map.of("parent",
                    Map.of("payload", "x".repeat(1024 * 1024), "leaf", "12")));
            leaves.add(root.getObject("parent").get("leaf"));
            JsonValue absent = root.get("parent").get("missing").getArray("items").get(0);
            missing.add(absent);
            try {
                absent.requireNonNull();
                throw new IllegalStateException("Missing requirement did not fail");
            } catch (JsonException failure) {
                failures.add(failure);
            }
        }
        for (int i = 0; i < leaves.size(); i++) {
            if (leaves.get(i).asInt() != 12 || !missing.get(i).isMissing()
                    || !failures.get(i).pointer().toString().equals("/parent/missing/items/0")) {
                throw new IllegalStateException("Retained access view changed");
            }
        }
        System.out.println("{\"engine\":\"" + args[0] + "\",\"allocatedAncestorMiB\":512,"
                + "\"retainedViews\":1024,\"retainedErrors\":512,\"heapBytes\":"
                + Runtime.getRuntime().maxMemory() + "}");
    }
}
