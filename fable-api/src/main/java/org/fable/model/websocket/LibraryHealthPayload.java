package org.fable.model.websocket;

import java.util.List;
import java.util.Map;

public record LibraryHealthPayload(Map<Long, Boolean> libraryHealth, Map<Long, List<String>> inaccessiblePaths) {
    public LibraryHealthPayload(Map<Long, Boolean> libraryHealth) {
        this(libraryHealth, Map.of());
    }
}
