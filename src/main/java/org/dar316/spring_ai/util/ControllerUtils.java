package org.dar316.spring_ai.util;

import org.dar316.spring_ai.dto.rag.RagHit;
import org.springframework.ai.document.Document;

import java.util.Map;

public final class ControllerUtils {

    private ControllerUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static RagHit toHit(Document document) {
        Map<String, Object> metadata =  document.getMetadata();

        return new RagHit(
                document.getText(),
                StringUtils.valueAsString(metadata.get("source")),
                StringUtils.valueAsString(metadata.get("technology")),
                StringUtils.valueAsString(metadata.get("technology_version")),
                NumberUtils.valueAsInteger(metadata.get("chunk_index")),
                NumberUtils.valueAsDouble(metadata.get("vector_score")),
                document.getScore()
        );
    }
}
