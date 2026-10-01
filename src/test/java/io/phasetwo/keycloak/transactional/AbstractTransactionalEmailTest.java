package io.phasetwo.keycloak.transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.jbosslog.JBossLog;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.internal.ResteasyClientBuilderImpl;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RealmRepresentation;
import org.testcontainers.containers.Network;
import org.testcontainers.images.PullPolicy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;

@JBossLog
public abstract class AbstractTransactionalEmailTest {

  static final String KEYCLOAK_IMAGE =
      String.format(
          "quay.io/phasetwo/keycloak-crdb:%s", System.getProperty("keycloak-version", "26.8.0"));
  static final String ADMIN_CLI = "admin-cli";
  static final String MASTER = "master";

  static final ObjectMapper MAPPER =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  static final Network network = Network.newNetwork();

  static final String[] EXTRA_DEPS = {};

  static List<File> getDeps() {
    List<File> deps = new ArrayList<>();
    for (String dep : EXTRA_DEPS) {
      deps.addAll(
          Maven.resolver()
              .loadPomFromFile("./pom.xml")
              .resolve(dep)
              .withoutTransitivity()
              .asList(File.class));
    }
    return deps;
  }

  static Keycloak keycloak;
  static ResteasyClient resteasyClient;

  static final KeycloakContainer container =
      new KeycloakContainer(KEYCLOAK_IMAGE)
          .withImagePullPolicy(PullPolicy.alwaysPull())
          .withContextPath("/auth")
          .withReuse(true)
          .withProviderClassesFrom("target/classes")
          .withProviderLibsFrom(getDeps())
          .withNetwork(network)
          .withAccessToHost(true)
          .withExposedPorts(8787, 9000, 8080)
          .withEnv(
              "JAVA_OPTS",
              "-agentlib:jdwp=transport=dt_socket,address=*:8787,server=y,suspend=n"
                  + " -XX:MetaspaceSize=96M -XX:MaxMetaspaceSize=256m");

  @BeforeAll
  static void beforeAll() {
    container.start();
    resteasyClient =
        new ResteasyClientBuilderImpl()
            .disableTrustManager()
            .readTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build();
    keycloak =
        Keycloak.getInstance(
            container.getAuthServerUrl(),
            MASTER,
            container.getAdminUsername(),
            container.getAdminPassword(),
            ADMIN_CLI);
  }

  @AfterAll
  static void afterAll() {
    container.stop();
    network.close();
  }

  private final List<String> createdRealms = new ArrayList<>();

  @BeforeEach
  void setupEach() {
    createdRealms.clear();
  }

  @AfterEach
  void cleanupRealms() {
    List.copyOf(createdRealms)
        .forEach(
            realmName -> {
              try {
                keycloak.realms().realm(realmName).remove();
              } catch (Exception e) {
                log.warnf("Failed to remove test realm %s: %s", realmName, e.getMessage());
              }
            });
  }

  protected RealmRepresentation importRealm(String jsonPath) {
    RealmRepresentation realm = loadJson(jsonPath, RealmRepresentation.class);
    given()
        .baseUri(container.getAuthServerUrl())
        .basePath("admin/realms/")
        .contentType("application/json")
        .auth()
        .oauth2(keycloak.tokenManager().getAccessTokenString())
        .body(realm)
        .when()
        .post()
        .then()
        .statusCode(201);
    createdRealms.add(realm.getRealm());
    log.infof("Imported realm: %s", realm.getRealm());
    return realm;
  }

  protected <T> T loadJson(String resourcePath, Class<T> type) {
    try (var stream = getClass().getResourceAsStream(resourcePath)) {
      assertThat("Resource not found: " + resourcePath, stream != null, is(true));
      return MAPPER.readValue(stream, type);
    } catch (Exception e) {
      throw new RuntimeException("Failed to load " + resourcePath, e);
    }
  }

  protected String baseUrl(String realm) {
    return container.getAuthServerUrl() + "realms/" + realm + "/ext-email-template";
  }

  protected String adminToken() {
    return keycloak.tokenManager().getAccessTokenString();
  }
}
