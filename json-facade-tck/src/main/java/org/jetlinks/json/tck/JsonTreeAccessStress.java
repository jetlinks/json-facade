package org.jetlinks.json.tck;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonPointer;
import org.jetlinks.json.JsonValue;

/** Standalone bounded-heap proof that retained child/missing views and validation errors do not
 * retain former ancestor storage. Run with -Xmx64m and either real Provider on the classpath.
 */
public final class JsonTreeAccessStress {
    private JsonTreeAccessStress() {
    }

    public static void main(String[] args) {
        JsonMapper mapper = Json.builder().provider(args[0]).build();
        boolean borrowed = args.length > 1 && args[1].equals("borrowed");
        List<JsonValue> leaves = new ArrayList<>();
        List<JsonValue> missing = new ArrayList<>();
        List<JsonValue> foreign = new ArrayList<>();
        List<JsonException> failures = new ArrayList<>();
        for (int i = 0; i < 512; i++) {
            Map<String, Object> source = Map.of("payload", "x".repeat(1024 * 1024),
                    "parent", Map.of("leaf", "12"));
            JsonValue root = borrowed ? mapper.wrap(source) : mapper.createObject(source);
            leaves.add(root.getObject("parent"));
            JsonValue absent = root.node("parent").node("missing").node("items").node(0);
            missing.add(absent);
            foreign.add(new ForeignAbsent(root).node(0));
            try {
                absent.requireNonNull();
                throw new IllegalStateException("Missing requirement did not fail");
            } catch (JsonException failure) {
                failures.add(failure);
            }
        }
        for (int i = 0; i < leaves.size(); i++) {
            if (leaves.get(i).node("leaf").asInt() != 12 || !missing.get(i).isMissing()
                    || !foreign.get(i).pointer().toString().equals("/external/items/0")
                    || !foreign.get(i).displayPath().equals("external.items[0]")
                    || !failures.get(i).pointer().toString().equals("/parent/missing/items/0")) {
                throw new IllegalStateException("Retained access view changed");
            }
        }
        System.out.println("{\"engine\":\"" + args[0] + "\",\"borrowed\":" + borrowed
                + ",\"allocatedAncestorMiB\":512,"
                + "\"retainedViews\":1536,\"retainedErrors\":512,\"heapBytes\":"
                + Runtime.getRuntime().maxMemory() + "}");
    }

    /** Foreign storage deliberately owns the entire source graph; a typed view must detach it. */
    private record ForeignAbsent(JsonValue owner) implements JsonValue {
        @Override
        public boolean isNull() {
            return false;
        }

        @Override
        public boolean isMissing() {
            return true;
        }

        @Override
        public Object toJavaValue() {
            throw new IllegalStateException("missing");
        }

        @Override
        public JsonPointer pointer() {
            return JsonPointer.of("/external/items");
        }

        @Override
        public String displayPath() {
            return "external.items";
        }

        @Override
        public JsonValue node(String name) {
            return owner.node("external").node("items").node(name);
        }

        @Override
        public JsonValue node(int index) {
            return owner.node("external").node("items").node(index);
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public JsonValue deepCopy() {
            return JsonValue.MISSING;
        }
    }
}
