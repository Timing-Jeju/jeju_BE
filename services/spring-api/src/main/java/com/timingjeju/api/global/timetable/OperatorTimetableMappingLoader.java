package com.timingjeju.api.global.timetable;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.timingjeju.api.application.timetable.OperatorTimetableMapping;
import com.timingjeju.api.application.timetable.TimetableParseException;
import java.io.InputStream;
import tools.jackson.databind.ObjectMapper;

public final class OperatorTimetableMappingLoader {
  private static final String SCHEMA = "/operator-mapping-v1.schema.json";
  private final ObjectMapper mapper;

  public OperatorTimetableMappingLoader(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public OperatorTimetableMapping load(byte[] json) {
    try (InputStream input = OperatorTimetableMappingLoader.class.getResourceAsStream(SCHEMA)) {
      if (input == null) throw new IllegalStateException("mapping schema missing");
      var schema =
          SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
              .getSchema(mapper.readTree(input));
      var value = mapper.readTree(json);
      if (!schema.validate(value).isEmpty()) {
        throw new TimetableParseException("OPERATOR_MAPPING_SCHEMA_MISMATCH", "sheet=<mapping>");
      }
      return mapper.treeToValue(value, OperatorTimetableMapping.class);
    } catch (TimetableParseException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new TimetableParseException("OPERATOR_MAPPING_INVALID", "sheet=<mapping>");
    }
  }
}
