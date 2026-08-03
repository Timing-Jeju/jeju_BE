package com.timingjeju.api.global.error;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProblemCodeRegistry {

  private final Map<String, ProblemDefinition> definitions;

  public ProblemCodeRegistry(List<ProblemDefinitionContributor> contributors) {
    LinkedHashMap<String, ProblemDefinition> registered = new LinkedHashMap<>();
    StandardProblemCode.definitions().forEach(definition -> register(registered, definition));
    contributors.forEach(
        contributor ->
            contributor.definitions().forEach(definition -> register(registered, definition)));
    definitions = Map.copyOf(registered);
  }

  public ProblemDefinition find(String code) {
    return definitions.getOrDefault(code, StandardProblemCode.INTERNAL_SERVER_ERROR.definition());
  }

  private static void register(
      Map<String, ProblemDefinition> registered, ProblemDefinition definition) {
    ProblemDefinition previous = registered.putIfAbsent(definition.code(), definition);
    if (previous != null) {
      throw new IllegalStateException("Duplicate problem code: " + definition.code());
    }
  }
}
