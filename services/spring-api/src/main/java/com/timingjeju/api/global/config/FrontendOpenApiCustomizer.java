package com.timingjeju.api.global.config;

import com.timingjeju.api.global.error.ProblemCodeRegistry;
import com.timingjeju.api.global.error.ProblemDefinition;
import com.timingjeju.api.global.logging.RequestTraceId;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Adds presentation-only metadata and examples after springdoc has inferred the runtime contract.
 */
@Component
final class FrontendOpenApiCustomizer {

  private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
  private static final String PROBLEM_SCHEMA = "#/components/schemas/ApiProblemDetails";
  private static final String TRACE_HEADER = "#/components/headers/TraceId";
  private static final Map<String, Object> PARAMETER_EXAMPLES =
      Map.ofEntries(
          Map.entry("Authorization", "Bearer <naver-provider-access-token>"),
          Map.entry("category", "content-type:12"),
          Map.entry("cursor", "eyJvZmZzZXQiOjIwfQ"),
          Map.entry("dateTime", "2026-08-25T12:00:00+09:00"),
          Map.entry("lat", 33.4996),
          Map.entry("lng", 126.5312),
          Map.entry("locale", "ko-KR"),
          Map.entry("placeId", "34000000-0000-4000-8000-000000000034"),
          Map.entry("query", "성산일출봉"),
          Map.entry("radiusMeters", 10000),
          Map.entry("regionCode", "jeju-seogwipo"),
          Map.entry("savedOnly", false),
          Map.entry("size", 20),
          Map.entry("sort", "saved_at_desc"),
          Map.entry("status", "draft"),
          Map.entry("tag", "오름"),
          Map.entry("tripId", "44000000-0000-4000-8000-000000000044"));
  private static final Map<String, ProblemDefinition> NON_CONTRIBUTOR_PROBLEM_DEFINITIONS =
      Map.ofEntries(
          Map.entry(
              "INVALID_REQUEST",
              nonContributorProblem(
                  "INVALID_REQUEST", "요청 값이 올바르지 않습니다", 400, "관심 장소 요청 값을 확인해 주세요.")),
          Map.entry(
              "INVALID_QUERY_PARAMETER",
              nonContributorProblem(
                  "INVALID_QUERY_PARAMETER", "조회 조건이 올바르지 않습니다", 400, "관심 장소 조회 조건을 확인해 주세요.")),
          Map.entry(
              "PLACE_NOT_FOUND",
              nonContributorProblem("PLACE_NOT_FOUND", "장소를 찾을 수 없습니다", 404, "저장하려는 장소 정보가 없습니다.")),
          Map.entry(
              "IDEMPOTENCY_PAYLOAD_CONFLICT",
              nonContributorProblem(
                  "IDEMPOTENCY_PAYLOAD_CONFLICT",
                  "같은 멱등성 키의 요청 내용이 다릅니다",
                  409,
                  "새 Idempotency-Key로 다시 요청해 주세요.")),
          Map.entry(
              "SAVED_PLACE_VERSION_CONFLICT",
              nonContributorProblem(
                  "SAVED_PLACE_VERSION_CONFLICT",
                  "관심 장소가 이미 변경되었습니다",
                  409,
                  "최신 관심 장소를 조회한 뒤 다시 수정해 주세요.")),
          Map.entry(
              "SAVED_PLACE_NOT_FOUND",
              nonContributorProblem(
                  "SAVED_PLACE_NOT_FOUND", "관심 장소를 찾을 수 없습니다", 404, "요청한 관심 장소가 없거나 접근할 수 없습니다.")),
          Map.entry(
              "SAVED_PLACE_CONSTRAINT_VIOLATION",
              nonContributorProblem(
                  "SAVED_PLACE_CONSTRAINT_VIOLATION",
                  "관심 장소 값을 처리할 수 없습니다",
                  422,
                  "메모, 태그, 우선순위 또는 희망 Day 값을 확인해 주세요.")));

  private static final Map<String, OperationDocument> DOCUMENTS = operationDocuments();

  private final ObjectMapper objectMapper;
  private final ProblemCodeRegistry problemCodeRegistry;

  FrontendOpenApiCustomizer(ObjectMapper objectMapper, ProblemCodeRegistry problemCodeRegistry) {
    this.objectMapper = objectMapper;
    this.problemCodeRegistry = problemCodeRegistry;
  }

  void customise(OpenAPI openApi) {
    alignNullableCursorSchemas(openApi);
    addHeaderComponents(openApi);
    documentComponentProblems(openApi);
    DOCUMENTS.forEach((key, document) -> applyOperation(openApi, key, document));
    projectCanonicalContracts(openApi);
  }

  private void projectCanonicalContracts(OpenAPI openApi) {
    Map<String, Map<String, Object>> catalogEndpoints =
        endpointMap(readContractResource("/rest/catalog.json"));
    for (String domain :
        List.of(
            "profile-legal",
            "places",
            "weather-forecast",
            "saved-places",
            "trips",
            "preferences-transport")) {
      Map<String, Object> contract = readContractResource("/domains/" + domain + "/contract.json");
      Map<String, Object> schemas = objectMap(contract.get("schemas"));
      for (Map<String, Object> endpoint : objectMapList(contract.get("endpoints"))) {
        String key = endpointKey(endpoint);
        if (!DOCUMENTS.containsKey(key)) {
          continue;
        }
        Map<String, Object> catalogEndpoint = catalogEndpoints.get(key);
        if (catalogEndpoint == null) {
          throw new IllegalStateException("OpenAPI canonical catalog endpoint가 없습니다: " + key);
        }
        projectCanonicalOperation(openApi, key, catalogEndpoint, endpoint, schemas);
      }
    }
  }

  private void projectCanonicalOperation(
      OpenAPI openApi,
      String key,
      Map<String, Object> catalog,
      Map<String, Object> endpoint,
      Map<String, Object> schemas) {
    Operation operation = operation(openApi, key);
    if (operation == null) {
      return;
    }
    Map<String, Object> schemaNames = objectMap(catalog.get("schemas"));
    projectCanonicalParameters(openApi, operation, schemaNames, schemas, key);
    Object bodyName = schemaNames.get("body");
    if (bodyName != null && !"none".equals(bodyName)) {
      if (operation.getRequestBody() == null) {
        throw new IllegalStateException("OpenAPI canonical request body가 없습니다: " + key);
      }
      jsonMedia(operation.getRequestBody().getContent())
          .setSchema(canonicalSchema(String.valueOf(bodyName), schemas, key));
    }
    Object successSchema = endpoint.get("successSchema");
    if (successSchema == null) {
      successSchema = endpoint.get("responseSchema");
    }
    Collection<?> successStatuses = valueList(endpoint.get("successStatuses"));
    if (successStatuses.isEmpty()) {
      successStatuses = valueList(objectMap(endpoint.get("responses")).get("success"));
    }
    for (Object status : successStatuses) {
      ApiResponse response = operation.getResponses().get(String.valueOf(status));
      if (response != null && !"none".equals(successSchema)) {
        jsonMedia(response.getContent())
            .setSchema(canonicalSchema(String.valueOf(successSchema), schemas, key));
      }
    }
  }

  private void projectCanonicalParameters(
      OpenAPI openApi,
      Operation operation,
      Map<String, Object> schemaNames,
      Map<String, Object> schemas,
      String key) {
    Map<String, Parameter> parameters = new LinkedHashMap<>();
    if (operation.getParameters() != null) {
      for (int index = 0; index < operation.getParameters().size(); index++) {
        Parameter parameter = operation.getParameters().get(index);
        if (parameter.get$ref() != null) {
          String prefix = "#/components/parameters/";
          if (!parameter.get$ref().startsWith(prefix)) {
            throw new IllegalStateException("OpenAPI 외부 parameter ref는 허용하지 않습니다.");
          }
          parameter =
              openApi
                  .getComponents()
                  .getParameters()
                  .get(parameter.get$ref().substring(prefix.length()));
          operation.getParameters().set(index, parameter);
        }
        parameters.put(parameter.getIn() + " " + parameter.getName(), parameter);
      }
    }
    for (Map.Entry<String, String> kind :
        Map.of("query", "query", "path", "path", "headers", "header").entrySet()) {
      Object schemaName = schemaNames.get(kind.getKey());
      if (schemaName == null
          || "none".equals(schemaName)
          || ("headers".equals(kind.getKey()) && "CommonHeaders".equals(schemaName))) {
        continue;
      }
      Map<String, Object> canonical = resolveCanonical(String.valueOf(schemaName), schemas, key);
      Set<String> required = Set.copyOf(stringList(canonical.get("required")));
      for (Map.Entry<String, Object> property : objectMap(canonical.get("properties")).entrySet()) {
        if ("Authorization".equals(property.getKey())) {
          continue;
        }
        Parameter parameter = parameters.get(kind.getValue() + " " + property.getKey());
        if (parameter == null) {
          throw new IllegalStateException(
              "OpenAPI canonical parameter가 없습니다: " + key + " " + property.getKey());
        }
        parameter.setSchema(canonicalSchema(property.getValue(), schemas, key));
        parameter.setRequired(required.contains(property.getKey()));
      }
    }
  }

  private static Operation operation(OpenAPI openApi, String key) {
    String[] parts = key.split(" ", 2);
    PathItem pathItem = openApi.getPaths().get(parts[1]);
    return pathItem == null
        ? null
        : pathItem.readOperationsMap().get(PathItem.HttpMethod.valueOf(parts[0]));
  }

  private Schema<?> canonicalSchema(Object value, Map<String, Object> schemas, String key) {
    Map<String, Object> canonical =
        value instanceof String name
            ? resolveCanonical(name, schemas, key)
            : expandCanonical(objectMap(value), schemas, key, Set.of());
    canonical = flattenClosedComposition(canonical, key);
    try {
      Schema<?> schema =
          objectMapper.readValue(objectMapper.writeValueAsString(canonical), Schema.class);
      restoreCanonicalTypes(schema, canonical);
      return schema;
    } catch (JacksonException exception) {
      throw new IllegalStateException("OpenAPI canonical schema 변환에 실패했습니다: " + key, exception);
    }
  }

  private static Map<String, Object> flattenClosedComposition(
      Map<String, Object> canonical, String key) {
    if (!Boolean.FALSE.equals(canonical.get("unevaluatedProperties"))) {
      return canonical;
    }
    Map<String, Object> flattened = new LinkedHashMap<>(canonical);
    flattened.remove("unevaluatedProperties");
    flattened.put("additionalProperties", false);
    Object composition = flattened.remove("allOf");
    if (!(composition instanceof List<?> members)) {
      return flattened;
    }
    flattened.putIfAbsent("type", "object");
    Set<String> required = new LinkedHashSet<>(stringList(flattened.get("required")));
    Map<String, Object> properties = new LinkedHashMap<>(objectMap(flattened.get("properties")));
    for (Object member : members) {
      Map<String, Object> schema = objectMap(member);
      required.addAll(stringList(schema.get("required")));
      for (Map.Entry<String, Object> property : objectMap(schema.get("properties")).entrySet()) {
        Object previous = properties.putIfAbsent(property.getKey(), property.getValue());
        if (previous != null && !previous.equals(property.getValue())) {
          throw new IllegalStateException("OpenAPI canonical allOf property가 충돌합니다: " + key);
        }
      }
    }
    flattened.put("required", List.copyOf(required));
    flattened.put("properties", properties);
    return flattened;
  }

  private static void restoreCanonicalTypes(Schema<?> schema, Map<String, Object> canonical) {
    if (canonical.get("type") instanceof String type) {
      schema.setType(type);
      boolean nullable = Boolean.TRUE.equals(canonical.get("nullable"));
      schema.setTypes(nullable ? Set.of(type, "null") : Set.of(type));
      schema.setNullable(nullable);
    }
    if (canonical.get("exclusiveMinimum") instanceof Number minimum) {
      schema.setExclusiveMinimumValue(new BigDecimal(minimum.toString()));
    }
    if (canonical.get("exclusiveMaximum") instanceof Number maximum) {
      schema.setExclusiveMaximumValue(new BigDecimal(maximum.toString()));
    }
    Map<String, Schema> properties = schema.getProperties();
    if (properties != null) {
      Map<String, Object> canonicalProperties = objectMap(canonical.get("properties"));
      properties.forEach(
          (name, property) ->
              restoreCanonicalTypes(property, objectMap(canonicalProperties.get(name))));
    }
    if (schema.getItems() != null) {
      restoreCanonicalTypes(schema.getItems(), objectMap(canonical.get("items")));
    }
  }

  private Map<String, Object> resolveCanonical(
      String name, Map<String, Object> schemas, String key) {
    Object raw = schemas.get(name);
    if (!(raw instanceof Map<?, ?>)) {
      throw new IllegalStateException("OpenAPI canonical schema가 없습니다: " + key + " " + name);
    }
    return expandCanonical(objectMap(raw), schemas, key, Set.of(name));
  }

  private Map<String, Object> expandCanonical(
      Map<String, Object> raw, Map<String, Object> schemas, String key, Set<String> seen) {
    if (raw.containsKey("$ref")) {
      String name = String.valueOf(raw.get("$ref"));
      if (seen.contains(name)) {
        throw new IllegalStateException("OpenAPI canonical schema ref가 순환합니다: " + key + " " + name);
      }
      Map<String, Object> referenced = objectMap(schemas.get(name));
      if (referenced.isEmpty()) {
        throw new IllegalStateException("OpenAPI canonical schema ref가 없습니다: " + key + " " + name);
      }
      Map<String, Object> merged = new LinkedHashMap<>(referenced);
      raw.forEach(
          (childKey, child) -> {
            if (!"$ref".equals(childKey)) {
              merged.put(childKey, child);
            }
          });
      raw = merged;
      seen = new java.util.HashSet<>(seen);
      seen.add(name);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    Set<String> currentSeen = seen;
    for (Map.Entry<String, Object> entry : raw.entrySet()) {
      Object child = entry.getValue();
      if (child instanceof Map<?, ?>) {
        child = expandCanonical(objectMap(child), schemas, key, currentSeen);
      } else if (child instanceof List<?> values) {
        child =
            values.stream()
                .map(
                    item ->
                        item instanceof Map<?, ?>
                            ? expandCanonical(objectMap(item), schemas, key, currentSeen)
                            : item)
                .toList();
      }
      result.put(entry.getKey(), child);
    }
    return result;
  }

  private Map<String, Object> readContractResource(String path) {
    try (InputStream input = FrontendOpenApiCustomizer.class.getResourceAsStream(path)) {
      if (input == null) {
        throw new IllegalStateException("OpenAPI canonical contract resource가 없습니다: " + path);
      }
      return objectMap(objectMapper.readValue(input, Map.class));
    } catch (IOException exception) {
      throw new IllegalStateException("OpenAPI canonical contract를 읽을 수 없습니다: " + path, exception);
    }
  }

  private static Map<String, Map<String, Object>> endpointMap(Map<String, Object> contract) {
    Map<String, Map<String, Object>> result = new LinkedHashMap<>();
    objectMapList(contract.get("endpoints"))
        .forEach(endpoint -> result.put(endpointKey(endpoint), endpoint));
    return result;
  }

  private static String endpointKey(Map<String, Object> endpoint) {
    return endpoint.get("method") + " " + endpoint.get("path");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> objectMap(Object value) {
    return value instanceof Map<?, ?> ? (Map<String, Object>) value : new LinkedHashMap<>();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> objectMapList(Object value) {
    return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
  }

  private static List<?> valueList(Object value) {
    return value instanceof List<?> values ? values : List.of();
  }

  private static List<String> stringList(Object value) {
    return value instanceof List<?> values
        ? values.stream().map(String::valueOf).toList()
        : List.of();
  }

  private static void alignNullableCursorSchemas(OpenAPI openApi) {
    openApi.getComponents().getSchemas().values().stream()
        .filter(schema -> schema.getProperties() != null)
        .map(schema -> (Schema<?>) schema.getProperties().get("nextCursor"))
        .filter(java.util.Objects::nonNull)
        .forEach(nextCursor -> nextCursor.setTypes(Set.of("string", "null")));
  }

  private void applyOperation(OpenAPI openApi, String key, OperationDocument document) {
    String[] parts = key.split(" ", 2);
    PathItem pathItem = openApi.getPaths().get(parts[1]);
    if (pathItem == null) {
      return;
    }
    Operation operation = pathItem.readOperationsMap().get(PathItem.HttpMethod.valueOf(parts[0]));
    if (operation == null) {
      return;
    }
    operation.setOperationId(document.operationId());
    operation.setTags(List.of(document.tag()));
    if (key.equals("POST /api/v1/me/saved-places")) {
      operation.setDescription(
          "Idempotency-Key로 중복 생성을 방지하며, 같은 key와 같은 payload는 기존 결과를 replay합니다.");
    } else if (key.equals("DELETE /api/v1/me/saved-places/{placeId}")) {
      operation.setDescription("관심 장소를 삭제합니다. request body와 성공 response content는 없습니다.");
    }
    documentConditionalHeaders(key, operation);
    documentParameters(operation);
    if (document.requestExample() != null && operation.getRequestBody() != null) {
      Content requestContent = operation.getRequestBody().getContent();
      if (requestContent == null) {
        requestContent = new Content();
        operation.getRequestBody().setContent(requestContent);
      }
      MediaType requestMedia = jsonMedia(requestContent);
      requestMedia.setExample(readJson(document.requestExample()));
    }
    document
        .errorCodes()
        .forEach(
            (status, code) ->
                operation
                    .getResponses()
                    .putIfAbsent(status, new ApiResponse().description(code + " 오류")));
    operation
        .getResponses()
        .forEach(
            (status, response) -> {
              if (status.startsWith("2")) {
                if (!"204".equals(status) && document.successExample() != null) {
                  Content successContent = response.getContent();
                  if (successContent == null) {
                    successContent = new Content();
                    response.setContent(successContent);
                  }
                  MediaType successMedia = jsonMedia(successContent);
                  successMedia.setExample(readJson(document.successExample()));
                  response.addHeaderObject(
                      RequestTraceId.TRACE_ID_HEADER, new Header().$ref(TRACE_HEADER));
                }
              } else if (status.startsWith("4") || status.startsWith("5")) {
                documentProblem(
                    response, Integer.parseInt(status), document.errorCodes().get(status), key);
              }
            });
  }

  private static void documentParameters(Operation operation) {
    if (operation.getParameters() == null) {
      return;
    }
    operation
        .getParameters()
        .forEach(
            parameter -> {
              if (parameter.get$ref() != null) {
                return;
              }
              String name = parameter.getName();
              if (parameter.getDescription() == null || parameter.getDescription().isBlank()) {
                parameter.setDescription(parameterDescription(name));
              }
              if (parameter.getExample() == null && PARAMETER_EXAMPLES.containsKey(name)) {
                parameter.setExample(PARAMETER_EXAMPLES.get(name));
              }
            });
  }

  private static void documentConditionalHeaders(String key, Operation operation) {
    if (key.equals("POST /api/v1/me/saved-places")) {
      mergeRequiredHeader(
          operation,
          "Idempotency-Key",
          "관심 장소 생성 요청 replay를 식별하는 공개 가능한 key",
          new StringSchema().pattern("^[A-Za-z0-9._:-]{1,128}$"),
          "saved-place-create-34");
    } else if (key.equals("POST /api/v1/trips")) {
      mergeRequiredHeader(
          operation,
          "Idempotency-Key",
          "여행 생성 요청을 24시간 동안 식별하는 lowercase canonical UUID입니다.",
          new StringSchema().format("uuid"),
          "44000000-0000-4000-8000-000000000044");
    } else if (key.equals("GET /api/v1/trips")) {
      setParameterExample(operation, "sort", "updated_at_desc");
    } else if (key.equals("PUT /api/v1/trips/{tripId}/preferences")) {
      mergeRequiredHeader(
          operation,
          "If-Match",
          "직전 여행 응답의 강한 ETag를 큰따옴표까지 그대로 전달",
          new StringSchema().pattern("^\\\"[A-Za-z0-9._:-]{1,128}\\\"$"),
          "\"trip-current-v1\"");
    }
    if (key.equals("PATCH /api/v1/me/saved-places/{placeId}")) {
      mergeRequiredHeader(
          operation,
          "If-Match",
          "직전 관심 장소 응답 ETag를 큰따옴표까지 그대로 전달",
          new StringSchema().pattern("^\\\"[A-Za-z0-9._:-]{1,128}\\\"$"),
          "\"saved-place.34.v1\"");
    }
    if (key.equals("POST /api/v1/me/saved-places")) {
      addResponseHeaderReferences(
          operation, List.of("200", "201"), List.of("Location", "ETag", "Idempotency-Replayed"));
    } else if (key.equals("PATCH /api/v1/me/saved-places/{placeId}")) {
      addResponseHeaderReferences(operation, List.of("200"), List.of("ETag"));
    } else if (key.equals("POST /api/v1/trips")) {
      addResponseHeaderReferences(
          operation, List.of("201"), List.of("Location", "ETag", "Idempotency-Replayed"));
    } else if (key.equals("PUT /api/v1/trips/{tripId}/preferences")) {
      addResponseHeaderReferences(operation, List.of("200"), List.of("ETag"));
    }
  }

  private static void mergeRequiredHeader(
      Operation operation, String name, String description, Schema<?> schema, Object example) {
    Parameter exact = requiredRequestHeader(name, description, schema, example);
    if (operation.getParameters() == null) {
      operation.addParametersItem(exact);
      return;
    }
    for (int index = 0; index < operation.getParameters().size(); index++) {
      Parameter current = operation.getParameters().get(index);
      if (name.equals(current.getName())
          || ("#/components/parameters/" + name).equals(current.get$ref())) {
        operation.getParameters().set(index, exact);
        return;
      }
    }
    operation.addParametersItem(exact);
  }

  private static void setParameterExample(Operation operation, String name, Object example) {
    if (operation.getParameters() == null) {
      return;
    }
    operation.getParameters().stream()
        .filter(parameter -> name.equals(parameter.getName()))
        .findFirst()
        .ifPresent(parameter -> parameter.setExample(example));
  }

  private static void addResponseHeaderReferences(
      Operation operation, List<String> statuses, List<String> names) {
    statuses.stream()
        .map(operation.getResponses()::get)
        .filter(java.util.Objects::nonNull)
        .forEach(
            response ->
                names.forEach(
                    name ->
                        response.addHeaderObject(
                            name, new Header().$ref("#/components/headers/" + name))));
  }

  private static String parameterDescription(String name) {
    return switch (name) {
      case "Authorization" ->
          "Bearer 인증 값. endpoint 설명에 따라 Supabase JWT 또는 Naver provider token을 전달합니다.";
      case "category" -> "공개 canonical 장소 category";
      case "cursor" -> "직전 응답 nextCursor를 해석하지 않고 그대로 전달하는 opaque cursor";
      case "dateTime" -> "Asia/Seoul 정시를 +09:00 offset으로 표현한 예보 시각";
      case "lat", "lng" -> "WGS84 제주 위경도";
      case "locale" -> "법정 문서 locale. 생략 기본값은 ko-KR";
      case "placeId" -> "lowercase canonical UUID 장소 식별자";
      case "query" -> "trim 적용 검색어";
      case "radiusMeters" -> "좌표 중심 검색 반경(m)";
      case "regionCode" -> "정규화 제주 지역 code";
      case "savedOnly" -> "인증 사용자의 저장 장소만 조회할지 여부";
      case "size" -> "한 page의 최대 item 수";
      default -> name + " 요청 조건";
    };
  }

  private static MediaType jsonMedia(Content content) {
    MediaType media = content.get("application/json");
    if (media == null) {
      media = content.remove("*/*");
    }
    if (media == null) {
      media = new MediaType();
    }
    content.addMediaType("application/json", media);
    return media;
  }

  private void documentProblem(
      ApiResponse response, int status, String configuredCode, String operationKey) {
    if (response.get$ref() != null) {
      return;
    }
    String code = configuredCode == null ? defaultCode(status) : configuredCode;
    response.addHeaderObject(RequestTraceId.TRACE_ID_HEADER, new Header().$ref(TRACE_HEADER));
    response.setContent(
        new Content()
            .addMediaType(
                org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                new MediaType()
                    .schema(new Schema<>().$ref(PROBLEM_SCHEMA))
                    .example(problemExample(status, code, operationKey))));
  }

  private void documentComponentProblems(OpenAPI openApi) {
    Map<String, Integer> statuses =
        Map.of(
            "ValidationProblem", 400,
            "AuthenticationProblem", 401,
            "AccessDeniedProblem", 403,
            "NotFoundProblem", 404,
            "ConflictProblem", 409,
            "UpstreamProblem", 503,
            "InternalServerProblem", 500);
    statuses.forEach(
        (name, status) -> {
          ApiResponse response = openApi.getComponents().getResponses().get(name);
          if (response != null) {
            documentProblem(response, status, defaultCode(status), null);
          }
        });
  }

  private Map<String, Object> problemExample(int status, String code, String operationKey) {
    boolean savedPlaceOperation =
        operationKey != null && operationKey.contains("/api/v1/me/saved-places");
    ProblemDefinition definition =
        savedPlaceOperation ? NON_CONTRIBUTOR_PROBLEM_DEFINITIONS.get(code) : null;
    if (definition == null) {
      definition = problemCodeRegistry.find(code);
    }
    if (definition == null || definition.status() != status || !definition.code().equals(code)) {
      throw new IllegalStateException(
          "OpenAPI Problem code/status가 runtime registry와 다릅니다: " + code);
    }
    Map<String, Object> example = new LinkedHashMap<>();
    example.put("type", definition.type().toString());
    example.put("title", definition.title());
    example.put("status", definition.status());
    example.put("detail", definition.detail());
    example.put("instance", "urn:timing-jeju:problem:" + TRACE_ID);
    example.put("code", code);
    example.put("traceId", TRACE_ID);
    example.put("fieldErrors", List.of());
    return example;
  }

  private static ProblemDefinition nonContributorProblem(
      String code, String title, int status, String detail) {
    return new ProblemDefinition(
        URI.create(
            "https://api.timing-jeju.com/problems/"
                + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')),
        title,
        status,
        code,
        detail);
  }

  private static String defaultCode(int status) {
    return switch (status) {
      case 400 -> "VALIDATION_FAILED";
      case 401 -> "AUTHENTICATION_REQUIRED";
      case 403 -> "AUTH_ACCESS_DENIED";
      case 404 -> "RESOURCE_NOT_FOUND";
      case 409 -> "CONFLICT";
      case 422 -> "UNPROCESSABLE_ENTITY";
      case 429 -> "TOO_MANY_REQUESTS";
      case 502 -> "UPSTREAM_ERROR";
      case 503 -> "SERVICE_UNAVAILABLE";
      case 504 -> "UPSTREAM_TIMEOUT";
      default -> "INTERNAL_SERVER_ERROR";
    };
  }

  private Object readJson(String value) {
    try {
      return objectMapper.readValue(value, Object.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("OpenAPI frontend example JSON이 유효하지 않습니다.", exception);
    }
  }

  private static void addHeaderComponents(OpenAPI openApi) {
    openApi
        .getComponents()
        .addHeaders(
            "TraceId",
            requiredHeader(
                "서버가 요청 단위로 생성한 추적 식별자", new StringSchema().pattern("^[0-9a-f]{32}$"), TRACE_ID))
        .addHeaders(
            "Location",
            requiredHeader(
                "생성한 리소스의 상대 URI",
                new StringSchema().format("uri"),
                "/api/v1/trips/44000000-0000-4000-8000-000000000044"))
        .addHeaders(
            "ETag",
            requiredHeader(
                "큰따옴표를 포함한 strong opaque validator",
                new StringSchema().pattern("^\\\"[A-Za-z0-9._:-]{1,128}\\\"$"),
                "\"resource.v1\""))
        .addHeaders(
            "Idempotency-Replayed",
            requiredHeader("동일 요청의 저장된 응답 replay 여부", new BooleanSchema(), Boolean.FALSE));
    openApi
        .getComponents()
        .addParameters(
            "If-Match",
            requiredRequestHeader(
                "If-Match",
                "직전 응답 ETag를 큰따옴표까지 그대로 전달",
                new StringSchema().pattern("^\\\"[A-Za-z0-9._:-]{1,128}\\\"$"),
                "\"resource.v1\""))
        .addParameters(
            "Idempotency-Key",
            requiredRequestHeader(
                "Idempotency-Key",
                "생성 요청 replay를 식별하는 공개 가능한 key",
                new StringSchema().pattern("^[A-Za-z0-9._:-]{1,128}$"),
                "44000000-0000-4000-8000-000000000044"));
  }

  private static Header requiredHeader(String description, Schema<?> schema, Object example) {
    return new Header().description(description).required(true).schema(schema).example(example);
  }

  private static Parameter requiredRequestHeader(
      String name, String description, Schema<?> schema, Object example) {
    return new Parameter()
        .name(name)
        .in("header")
        .required(true)
        .description(description)
        .schema(schema)
        .example(example);
  }

  private static Map<String, OperationDocument> operationDocuments() {
    Map<String, OperationDocument> result = new LinkedHashMap<>();
    result.put(
        "GET /api/v1/auth/social/providers",
        doc(
            "authSocialProvidersList",
            "인증",
            null,
            """
            {"providers":[{"id":"google","displayName":"Google"},{"id":"kakao","displayName":"Kakao"},{"id":"custom:naver","displayName":"Naver"}]}
            """));
    result.put(
        "GET /api/v1/auth/social/naver/userinfo",
        doc(
            "authNaverUserInfoRead",
            "인증",
            null,
            """
            {"sub":"naver-public-subject-example","email":"naver-example@example.invalid","name":"제주 사용자","preferred_username":"제주 사용자","picture":"https://example.invalid/profile.png"}
            """,
            Map.of(
                "401", "SOCIAL_NAVER_TOKEN_INVALID",
                "403", "SOCIAL_NAVER_UPSTREAM_FORBIDDEN",
                "422", "SOCIAL_NAVER_EMAIL_REQUIRED",
                "429", "SOCIAL_NAVER_RATE_LIMITED",
                "502", "SOCIAL_NAVER_UPSTREAM_UNAVAILABLE",
                "503", "SOCIAL_NAVER_OVERLOADED",
                "504", "SOCIAL_NAVER_UPSTREAM_TIMEOUT")));
    result.put(
        "GET /api/v1/me",
        doc(
            "profileRead",
            "프로필",
            null,
            """
            {"userId":"18000000-0000-4000-8000-000000000018","email":"user@example.invalid","nickname":"제주 여행자","profileImageUrl":null,"locale":"ko-KR","providers":["google"],"onboardingCompleted":true,"updatedAt":"2026-08-25T00:00:00Z"}
            """,
            Map.of("401", "AUTHENTICATION_REQUIRED", "503", "PROFILE_DATA_UNAVAILABLE")));
    result.put(
        "PATCH /api/v1/me",
        doc(
            "profileUpdate",
            "프로필",
            """
            {"nickname":"제주 산책자","locale":"ko-KR"}
            """,
            """
            {"userId":"18000000-0000-4000-8000-000000000018","email":"user@example.invalid","nickname":"제주 산책자","profileImageUrl":null,"locale":"ko-KR","providers":["google"],"onboardingCompleted":true,"updatedAt":"2026-08-25T00:05:00Z"}
            """,
            Map.of(
                "400", "INVALID_PROFILE_LEGAL_REQUEST",
                "401", "AUTHENTICATION_REQUIRED",
                "409", "PROFILE_CONFLICT",
                "503", "PROFILE_DATA_UNAVAILABLE")));
    result.put(
        "GET /api/v1/legal-documents",
        doc(
            "legalDocumentsList",
            "법정 문서",
            null,
            """
            {"evaluatedAt":"2026-08-25T00:00:00Z","locale":"ko-KR","items":[{"documentId":"19000000-0000-4000-8000-000000000019","type":"terms","version":"1.0.0","title":"서비스 이용약관","contentUrl":"https://example.invalid/legal/terms","required":true,"effectiveAt":"2026-08-01T00:00:00Z"}]}
            """,
            Map.of(
                "400", "INVALID_PROFILE_LEGAL_REQUEST",
                "401", "INVALID_ACCESS_TOKEN",
                "503", "PROFILE_DATA_UNAVAILABLE")));
    result.put(
        "PUT /api/v1/me/consents",
        doc(
            "legalConsentsUpdate",
            "법정 문서",
            """
            {"consents":[{"documentId":"19000000-0000-4000-8000-000000000019","agreed":true}]}
            """,
            """
            {"requiredConsentsSatisfied":true,"updatedAt":"2026-08-25T00:00:00Z"}
            """,
            Map.of(
                "400", "INVALID_PROFILE_LEGAL_REQUEST",
                "401", "AUTHENTICATION_REQUIRED",
                "409", "PROFILE_CONFLICT",
                "422", "LEGAL_CONSENT_REQUIRED",
                "503", "PROFILE_DATA_UNAVAILABLE")));
    result.put(
        "GET /api/v1/places",
        doc(
            "placesList",
            "관광지",
            null,
            """
            {"items":[],"page":{"size":20,"hasNext":false,"nextCursor":null}}
            """,
            Map.of(
                "400", "INVALID_QUERY_PARAMETER",
                "401", "INVALID_ACCESS_TOKEN",
                "422", "PLACE_QUERY_CONSTRAINT_VIOLATION",
                "503", "PLACE_DATA_UNAVAILABLE")));
    result.put(
        "GET /api/v1/places/{placeId}",
        doc(
            "placesRead",
            "관광지",
            null,
            """
            {"placeId":"34000000-0000-4000-8000-000000000034","contentId":"126508","name":"성산일출봉","category":"content-type:12","regionCode":"jeju-seogwipo","regionLabel":"서귀포시","address":"제주특별자치도 서귀포시 성산읍","location":{"lat":33.458,"lng":126.942},"thumbnailUrl":null,"recommendedStayMinutes":90,"recommendedStaySource":"policy","recommendedStayPolicyVersion":null,"recommendedStayEffectiveAt":null,"recommendedStayUpdatedAt":null,"operationsSummary":null,"saved":{"value":false,"memo":null,"tags":[]},"overview":"제주의 대표 오름","contact":{"phone":null,"homepageUrl":null},"operations":{"operatingHoursText":null,"closedDaysText":null,"parkingText":null,"admissionFeeText":null},"images":[],"nearbyStops":[]}
            """,
            Map.of(
                "400", "INVALID_QUERY_PARAMETER",
                "401", "INVALID_ACCESS_TOKEN",
                "404", "PLACE_NOT_FOUND",
                "503", "PLACE_DATA_UNAVAILABLE")));
    result.put(
        "GET /api/v1/weather/forecast",
        doc(
            "weatherForecastRead",
            "날씨",
            null,
            """
            {"contractVersion":"1.0.0","grid":{"nx":53,"ny":38,"regionName":"제주시"},"provider":"KMA","providerApiVersion":"VilageFcstInfoService_2.0","forecastType":"village","baseDate":"2026-08-25","baseTime":"05:00","forecastedAt":"2026-08-25T05:00:00+09:00","validAt":"2026-08-25T12:00:00+09:00","temperatureC":27.5,"precipitationProbabilityPercent":20,"precipitationAmountMm":null,"precipitationType":"none","skyCode":"mostly_cloudy","humidityPercent":72,"windSpeedMps":3.4,"observedAt":"2026-08-25T05:10:00+09:00","expiresAt":"2026-08-25T08:00:00+09:00","stale":false,"fallbackUsed":false}
            """,
            Map.of(
                "400",
                "INVALID_WEATHER_FORECAST_QUERY",
                "401",
                "INVALID_ACCESS_TOKEN",
                "422",
                "WEATHER_FORECAST_HORIZON_NOT_SUPPORTED",
                "503",
                "WEATHER_FORECAST_UNAVAILABLE")));
    result.put(
        "GET /api/v1/me/saved-places",
        doc(
            "savedPlacesList",
            "관심 장소",
            null,
            """
            {"items":[{"placeId":"34000000-0000-4000-8000-000000000034","name":"새별오름","category":"content-type:12","regionLabel":"제주시","thumbnailUrl":"https://example.invalid/place.jpg","recommendedStayMinutes":90,"memo":"노을 시간 방문","tags":["노을","오름"],"priority":5,"targetDay":2,"savedAt":"2026-08-25T00:00:00Z","updatedAt":"2026-08-25T00:00:00Z"}],"page":{"size":20,"hasNext":false,"nextCursor":null}}
            """,
            Map.of("400", "INVALID_QUERY_PARAMETER", "401", "AUTHENTICATION_REQUIRED")));
    result.put(
        "POST /api/v1/me/saved-places",
        doc(
            "savedPlacesCreate",
            "관심 장소",
            """
            {"placeId":"34000000-0000-4000-8000-000000000034","memo":"노을 시간 방문","tags":["오름","노을"],"priority":5,"targetDay":2}
            """,
            """
            {"placeId":"34000000-0000-4000-8000-000000000034","name":"새별오름","category":"content-type:12","regionLabel":"제주시","thumbnailUrl":"https://example.invalid/place.jpg","recommendedStayMinutes":90,"memo":"노을 시간 방문","tags":["노을","오름"],"priority":5,"targetDay":2,"savedAt":"2026-08-25T00:00:00Z","updatedAt":"2026-08-25T00:00:00Z"}
            """,
            Map.of(
                "400", "INVALID_REQUEST",
                "401", "AUTHENTICATION_REQUIRED",
                "404", "PLACE_NOT_FOUND",
                "409", "IDEMPOTENCY_PAYLOAD_CONFLICT",
                "422", "SAVED_PLACE_CONSTRAINT_VIOLATION")));
    result.put(
        "PATCH /api/v1/me/saved-places/{placeId}",
        doc(
            "savedPlacesUpdate",
            "관심 장소",
            """
            {"memo":"노을 시간 방문","tags":["오름"],"priority":3,"targetDay":2}
            """,
            """
            {"placeId":"34000000-0000-4000-8000-000000000034","name":"새별오름","category":"content-type:12","regionLabel":"제주시","thumbnailUrl":"https://example.invalid/place.jpg","recommendedStayMinutes":90,"memo":"노을 시간 방문","tags":["오름"],"priority":3,"targetDay":2,"savedAt":"2026-08-25T00:00:00Z","updatedAt":"2026-08-25T00:05:00Z"}
            """,
            Map.of(
                "400", "INVALID_REQUEST",
                "401", "AUTHENTICATION_REQUIRED",
                "404", "SAVED_PLACE_NOT_FOUND",
                "409", "SAVED_PLACE_VERSION_CONFLICT",
                "422", "SAVED_PLACE_CONSTRAINT_VIOLATION")));
    result.put(
        "DELETE /api/v1/me/saved-places/{placeId}",
        doc(
            "savedPlacesDelete",
            "관심 장소",
            null,
            null,
            Map.of(
                "400", "INVALID_REQUEST",
                "401", "AUTHENTICATION_REQUIRED",
                "404", "SAVED_PLACE_NOT_FOUND")));
    result.put(
        "PUT /api/v1/me/push-devices/{deviceId}",
        doc(
            "pushDevicesUpdate",
            "푸시 알림",
            """
            {"platform":"IOS","registrationToken":"__REDACTED_REGISTRATION_TOKEN__","permissionStatus":"GRANTED","appVersion":"1.2.3","locale":"ko-KR","timeZone":"Asia/Seoul"}
            """,
            """
            {"deviceId":"11300000-0000-4000-8000-000000000101","platform":"IOS","permissionStatus":"GRANTED","active":true,"updatedAt":"2026-08-25T00:00:00Z"}
            """,
            Map.of(
                "400", "INVALID_PUSH_NOTIFICATION_REQUEST",
                "401", "AUTHENTICATION_REQUIRED",
                "403", "AUTH_ACCESS_DENIED",
                "500", "INTERNAL_SERVER_ERROR",
                "503", "PUSH_NOTIFICATION_DATA_UNAVAILABLE")));
    result.put(
        "DELETE /api/v1/me/push-devices/{deviceId}",
        doc(
            "pushDevicesDelete",
            "푸시 알림",
            null,
            null,
            Map.of(
                "400", "INVALID_PUSH_NOTIFICATION_REQUEST",
                "401", "AUTHENTICATION_REQUIRED",
                "403", "AUTH_ACCESS_DENIED",
                "500", "INTERNAL_SERVER_ERROR",
                "503", "PUSH_NOTIFICATION_DATA_UNAVAILABLE")));
    result.put(
        "GET /api/v1/me/notification-preferences",
        doc(
            "notificationPreferencesRead",
            "푸시 알림",
            null,
            """
            {"nextDestinationDepartureEnabled":false,"safetyBufferMinutes":10,"updatedAt":null}
            """,
            Map.of(
                "401", "AUTHENTICATION_REQUIRED",
                "403", "AUTH_ACCESS_DENIED",
                "500", "INTERNAL_SERVER_ERROR",
                "503", "PUSH_NOTIFICATION_DATA_UNAVAILABLE")));
    result.put(
        "PATCH /api/v1/me/notification-preferences",
        doc(
            "notificationPreferencesUpdate",
            "푸시 알림",
            """
            {"nextDestinationDepartureEnabled":true,"safetyBufferMinutes":10}
            """,
            """
            {"nextDestinationDepartureEnabled":true,"safetyBufferMinutes":10,"updatedAt":"2026-08-25T00:05:00Z"}
            """,
            Map.of(
                "400", "INVALID_PUSH_NOTIFICATION_REQUEST",
                "401", "AUTHENTICATION_REQUIRED",
                "403", "AUTH_ACCESS_DENIED",
                "500", "INTERNAL_SERVER_ERROR",
                "503", "PUSH_NOTIFICATION_DATA_UNAVAILABLE")));
    String tripExample =
        """
        {"tripId":"44000000-0000-4000-8000-000000000044","title":"제주 3박 4일","status":"draft","startDate":"2026-09-10","endDate":"2026-09-13","timezone":"Asia/Seoul","userPace":"normal","transportModes":[{"mode":"public_transit","priority":1,"primary":true}],"days":[{"dayId":"44000000-0000-4000-8001-000000000044","dayNo":1,"date":"2026-09-10"},{"dayId":"44000000-0000-4000-8002-000000000044","dayNo":2,"date":"2026-09-11"},{"dayId":"44000000-0000-4000-8003-000000000044","dayNo":3,"date":"2026-09-12"},{"dayId":"44000000-0000-4000-8004-000000000044","dayNo":4,"date":"2026-09-13"}],"activeScheduleVersionId":null,"totalScore":null,"scoreProvenance":null,"scheduleEffect":"none","regenerationRequired":false,"createdAt":"2026-08-25T00:00:00Z","updatedAt":"2026-08-25T00:00:00Z"}
        """;
    result.put(
        "GET /api/v1/trips",
        doc(
            "tripsList",
            "여행",
            null,
            """
            {"items":[{"tripId":"44000000-0000-4000-8000-000000000044","title":"제주 3박 4일","status":"draft","startDate":"2026-09-10","endDate":"2026-09-13","timezone":"Asia/Seoul","activeScheduleVersionId":null,"totalScore":null,"scoreProvenance":null,"createdAt":"2026-08-25T00:00:00Z","updatedAt":"2026-08-25T00:00:00Z"}],"page":{"size":20,"hasNext":false,"nextCursor":null}}
            """,
            Map.of(
                "400", "INVALID_QUERY_PARAMETER",
                "401", "AUTHENTICATION_REQUIRED",
                "503", "TRIP_DATA_UNAVAILABLE")));
    result.put(
        "POST /api/v1/trips",
        doc(
            "tripsCreate",
            "여행",
            """
            {"title":"제주 3박 4일","startDate":"2026-09-10","endDate":"2026-09-13","timezone":"Asia/Seoul","userPace":"normal","transportModes":[{"mode":"public_transit","priority":1,"primary":true}]}
            """,
            tripExample,
            Map.of(
                "400", "INVALID_REQUEST",
                "401", "AUTHENTICATION_REQUIRED",
                "409", "IDEMPOTENCY_KEY_REUSED",
                "422", "TRIP_CONSTRAINT_VIOLATION",
                "503", "TRIP_DATA_UNAVAILABLE")));
    result.put(
        "GET /api/v1/trips/{tripId}",
        doc(
            "tripsRead",
            "여행",
            null,
            tripExample,
            Map.of(
                "400", "INVALID_REQUEST",
                "401", "AUTHENTICATION_REQUIRED",
                "404", "TRIP_NOT_FOUND",
                "503", "TRIP_DATA_UNAVAILABLE")));
    result.put(
        "PUT /api/v1/trips/{tripId}/preferences",
        doc(
            "tripPreferencesUpdate",
            "여행",
            """
            {"preferredCategories":["tourist_attraction","cafe"],"arrivalRegionCode":"jeju-si","departureRegionCode":"seogwipo-si","preferredRegionCodes":["seongsan","aewol"],"startPlaceId":"34000000-0000-4000-8000-000000000034","endPlaceId":null,"transportModes":[{"mode":"public_transit","priority":1,"primary":true},{"mode":"rental_car","priority":2,"primary":false},{"mode":"taxi","priority":3,"primary":false}]}
            """,
            """
            {"tripId":"44000000-0000-4000-8000-000000000044","scheduleEffect":"none","regenerationRequired":false,"activeScheduleVersionId":null,"tripStatus":"draft","updatedAt":"2026-09-01T10:00:00+09:00","preferences":{"preferredCategories":["tourist_attraction","cafe"],"arrivalRegionCode":"jeju-si","departureRegionCode":"seogwipo-si","preferredRegionCodes":["seongsan","aewol"],"startPlaceId":"34000000-0000-4000-8000-000000000034","endPlaceId":null,"transportModes":[{"mode":"public_transit","priority":1,"primary":true},{"mode":"rental_car","priority":2,"primary":false},{"mode":"taxi","priority":3,"primary":false}]}}
            """,
            Map.of(
                "400", "INVALID_REQUEST",
                "401", "AUTHENTICATION_REQUIRED",
                "404", "TRIP_NOT_FOUND",
                "409", "TRIP_VERSION_CONFLICT",
                "422", "PREFERENCE_CONSTRAINT_VIOLATION",
                "503", "TRIP_DATA_UNAVAILABLE")));
    return Map.copyOf(result);
  }

  private static OperationDocument doc(
      String operationId, String tag, String requestExample, String successExample) {
    return new OperationDocument(operationId, tag, requestExample, successExample, Map.of());
  }

  private static OperationDocument doc(
      String operationId,
      String tag,
      String requestExample,
      String successExample,
      Map<String, String> errorCodes) {
    return new OperationDocument(operationId, tag, requestExample, successExample, errorCodes);
  }

  private record OperationDocument(
      String operationId,
      String tag,
      String requestExample,
      String successExample,
      Map<String, String> errorCodes) {}
}
