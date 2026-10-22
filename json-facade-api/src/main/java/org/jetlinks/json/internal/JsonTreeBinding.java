package org.jetlinks.json.internal;

import java.util.Objects;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;

/** Write-once assembly link carried by native binding context attributes, never static state.
 * Stateless native tree deserializers use the current context, so copied native configurations
 * can replace this link without retaining a former facade through captured deserializer closures.
 */
public final class JsonTreeBinding {
    private volatile JsonMapper mapper;

    /** Complete assembly before returning/publishing the facade. No subsequent changes are allowed. */
    public <T extends JsonMapper> T initialize(T value) {
        if (mapper != null) {
            throw new IllegalStateException("Tree binding already initialized");
        }
        mapper = Objects.requireNonNull(value, "mapper");
        return value;
    }

    public JsonMapper mapper() {
        JsonMapper result = mapper;
        if (result == null) {
            throw new JsonException(JsonException.Kind.CONFIGURATION, "Tree binding is not published");
        }
        return result;
    }
}
