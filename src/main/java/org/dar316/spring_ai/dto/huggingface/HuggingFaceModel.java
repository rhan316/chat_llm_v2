package org.dar316.spring_ai.dto.huggingface;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HuggingFaceModel(
        String id,
        Long downloads,
        Integer likes,
        List<String> tags,
        @JsonProperty("pipeline_tag") String pipelineTag,
        @JsonProperty("lastModified") String lastModified
) {

}
