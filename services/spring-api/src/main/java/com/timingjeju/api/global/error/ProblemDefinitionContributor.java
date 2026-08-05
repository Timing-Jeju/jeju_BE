package com.timingjeju.api.global.error;

import java.util.Collection;

@FunctionalInterface
public interface ProblemDefinitionContributor {

  Collection<ProblemDefinition> definitions();
}
