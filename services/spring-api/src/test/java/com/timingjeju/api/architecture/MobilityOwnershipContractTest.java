package com.timingjeju.api.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class MobilityOwnershipContractTest {

  private static final Path REPOSITORY_ROOT = Path.of("../..").toAbsolutePath().normalize();
  private static final Path MAIN_JAVA =
      Path.of("src/main/java/com/timingjeju/api").toAbsolutePath().normalize();

  @Test
  void route계산_cache_fallback은_AI만_소유하고_Spring중복구현은_없다() throws IOException {
    assertThat(javaFiles(MAIN_JAVA.resolve("application/mobility"))).isEmpty();
    assertThat(
            javaFiles(
                Path.of("src/test/java/com/timingjeju/api/application/mobility")
                    .toAbsolutePath()
                    .normalize()))
        .isEmpty();
    assertThat(REPOSITORY_ROOT.resolve("docs/contracts/domains/mobility-route/contract.md"))
        .doesNotExist();

    String architectureTest =
        Files.readString(
            Path.of("src/test/java/com/timingjeju/api/architecture/ArchitectureTest.java"));
    assertThat(architectureTest)
        .doesNotContain("application.mobility", "mobility_route_application");

    String ownershipAdr =
        Files.readString(REPOSITORY_ROOT.resolve("docs/adr/0052-private-mcp-data-ownership.md"));
    String springMcpContract =
        Files.readString(
            REPOSITORY_ROOT.resolve(
                "docs/designs/timing-jeju-spring-fastapi-integration-contract.md"));
    assertThat(ownershipAdr)
        .contains("planner 경로 계산·route fact·TTL cache·fallback은 `jeju_AI`가 소유한다")
        .doesNotContain("기존 BE route provider(#41)는 별도 BE 기능으로 유지");
    assertThat(springMcpContract)
        .contains("route 계산·route fact·TTL cache·fallback은 AI가 소유한다")
        .contains("Spring은 private MCP 연결·계약 검증·감사와 제품 DB 결과 저장을 소유한다");
  }

  @Test
  void MCP_보안계약감사와_공개endpoint_migration_inventory는_유지된다() throws IOException {
    assertThat(
            List.of(
                "McpContractGuard.java",
                "McpEndpointPolicy.java",
                "McpPrivateRequestFilter.java",
                "McpServiceJwtIssuer.java",
                "JdbcMcpCallAuditWriter.java",
                "SpringAiJejuMcpClient.java"))
        .allSatisfy(
            file -> assertThat(MAIN_JAVA.resolve("global/mcp").resolve(file)).isRegularFile());

    assertThat(controllerInventory())
        .containsExactly(
            "domain/auth/controller/SocialLoginController.java",
            "domain/demo/controller/DemoImportController.java",
            "domain/legal/controller/LegalProfileController.java",
            "domain/notification/controller/PushNotificationController.java",
            "domain/places/controller/PlacesController.java",
            "domain/profile/controller/CurrentUserProfileController.java",
            "domain/savedplaces/controller/SavedPlacesController.java",
            "domain/schedule/controller/ScheduleController.java",
            "domain/schedule/controller/ScheduleMutationController.java",
            "domain/trip/controller/TripController.java",
            "domain/weather/controller/WeatherForecastController.java");
    assertThat(mappingAnnotationCount()).isEqualTo(37);
    assertThat(migrationInventory()).hasSize(35);
    assertThat(migrationInventory().getFirst())
        .isEqualTo("20260728000000_initial_public_schema.sql");
    assertThat(migrationInventory().getLast())
        .isEqualTo("20260907000000_schedule_item_create_contract.sql");
  }

  private static List<Path> javaFiles(Path directory) throws IOException {
    if (Files.notExists(directory)) {
      return List.of();
    }
    try (var paths = Files.walk(directory)) {
      return paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
    }
  }

  private static List<String> controllerInventory() throws IOException {
    try (var paths = Files.walk(MAIN_JAVA.resolve("domain"))) {
      return paths
          .filter(path -> path.getFileName().toString().endsWith("Controller.java"))
          .map(MAIN_JAVA::relativize)
          .map(Path::toString)
          .sorted()
          .toList();
    }
  }

  private static long mappingAnnotationCount() throws IOException {
    long count = 0;
    for (Path controller : javaFiles(MAIN_JAVA.resolve("domain"))) {
      count +=
          Files.readAllLines(controller).stream()
              .map(String::trim)
              .filter(
                  line ->
                      line.startsWith("@GetMapping")
                          || line.startsWith("@PostMapping")
                          || line.startsWith("@PutMapping")
                          || line.startsWith("@PatchMapping")
                          || line.startsWith("@DeleteMapping")
                          || line.startsWith("@RequestMapping"))
              .count();
    }
    return count;
  }

  private static List<String> migrationInventory() throws IOException {
    try (var paths = Files.list(REPOSITORY_ROOT.resolve("supabase/migrations"))) {
      return paths
          .filter(path -> path.getFileName().toString().endsWith(".sql"))
          .map(path -> path.getFileName().toString())
          .sorted()
          .toList();
    }
  }
}
