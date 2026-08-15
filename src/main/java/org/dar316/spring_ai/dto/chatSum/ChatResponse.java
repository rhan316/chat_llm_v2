package org.dar316.spring_ai.dto.chatSum;

import java.util.List;

public record ChatResponse(List<Choice> choices) {
}
