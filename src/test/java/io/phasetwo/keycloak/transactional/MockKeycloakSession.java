package io.phasetwo.keycloak.transactional;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.keycloak.connections.httpclient.HttpClientProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.provider.Provider;
import org.keycloak.models.ThemeManager;
import org.keycloak.theme.Theme;

/**
 * Minimal {@link KeycloakSession} stub for unit tests that exercise provider logic without
 * requiring a full Keycloak container.
 *
 * <p>Uses JDK dynamic proxies so only the methods actually called during a send are implemented;
 * all others throw {@link UnsupportedOperationException}.
 *
 * <p>Supports {@code session.getProvider(HttpClientProvider.class)} so that {@code SimpleHttp} can
 * make real HTTP calls to the in-process mock server, {@code session.getProvider(X.class)}
 * (single-arg) for a registered singleton (e.g. {@code FreeMarkerProvider}, only ever dereferenced
 * by {@code FreeMarkerEmailTemplateProvider}'s constructor, not by any of the paths these tests
 * exercise), {@code session.getProvider(X.class, id)} for a registered by-ID provider (e.g. a fake
 * {@code TransactionalEmailProvider}), and a mock {@code UserModel} for tests that need a recipient
 * (e.g. locale-based template routing).
 */
class MockKeycloakSession {

  private final Map<String, String> realmAttributes = new HashMap<>();
  private final Map<String, String> smtpConfig = new HashMap<>();
  private final Map<String, String> userAttributes = new HashMap<>();
  private final Map<Class<?>, Object> singletonProviders = new HashMap<>();
  private final Map<String, Object> providersById = new HashMap<>();
  private final Map<String, Properties> loginThemeMessagesByLocale = new HashMap<>();
  private final CloseableHttpClient httpClient = HttpClients.createDefault();

  private String realmName = "test-realm";
  private String realmDisplayName;
  private String realmDefaultLocale;
  private String userEmail = "user@example.com";
  private String userFirstName;
  private String userLastName;
  private String username = "testuser";

  void setAttribute(String key, String value) {
    realmAttributes.put(key, value);
  }

  void setRealmDefaultLocale(String locale) {
    this.realmDefaultLocale = locale;
  }

  void setSmtpConfig(String key, String value) {
    smtpConfig.put(key, value);
  }

  void setUserAttribute(String key, String value) {
    userAttributes.put(key, value);
  }

  /** Sets a login-theme message-bundle entry for a given locale (e.g. "nl", "updatePasswordTitle",
   * "Wachtwoord bijwerken"), as returned by {@code Theme.getMessages}/{@code getEnhancedMessages}. */
  void setLoginThemeMessage(String locale, String key, String value) {
    loginThemeMessagesByLocale.computeIfAbsent(locale, k -> new Properties()).setProperty(key, value);
  }

  void setUserEmail(String email) {
    this.userEmail = email;
  }

  <T extends Provider> void registerProvider(Class<T> providerClass, T instance) {
    singletonProviders.put(providerClass, instance);
  }

  <T extends Provider> void registerProvider(Class<T> providerClass, String id, T instance) {
    providersById.put(providerClass.getName() + "/" + id, instance);
  }

  KeycloakSession asSession() {
    RealmModel realm = mockRealm();
    KeycloakContext context = mockContext(realm);
    HttpClientProvider httpClientProvider = mockHttpClientProvider();
    return proxy(
        KeycloakSession.class,
        (proxy, method, args) -> {
          if ("getContext".equals(method.getName())) return context;
          if ("theme".equals(method.getName())) return mockThemeManager();
          if ("getProvider".equals(method.getName()) && args != null && args.length == 1) {
            if (HttpClientProvider.class.equals(args[0])) return httpClientProvider;
            // Registered singletons (e.g. FreeMarkerProvider, looked up unconditionally by
            // FreeMarkerEmailTemplateProvider's constructor) - null is a valid, safe default for
            // any provider not actually dereferenced by the code path under test.
            return singletonProviders.get(args[0]);
          }
          if ("getProvider".equals(method.getName()) && args != null && args.length == 2) {
            return providersById.get(((Class<?>) args[0]).getName() + "/" + args[1]);
          }
          throw new UnsupportedOperationException("MockKeycloakSession: " + method.getName());
        });
  }

  UserModel asUser() {
    return proxy(
        UserModel.class,
        (proxy, method, args) -> {
          switch (method.getName()) {
            case "getFirstAttribute":
              return userAttributes.get((String) args[0]);
            case "getEmail":
              return userEmail;
            case "getFirstName":
              return userFirstName;
            case "getLastName":
              return userLastName;
            case "getUsername":
              return username;
            default:
              throw new UnsupportedOperationException("MockUser: " + method.getName());
          }
        });
  }

  void setUserFirstName(String firstName) {
    this.userFirstName = firstName;
  }

  void setUserLastName(String lastName) {
    this.userLastName = lastName;
  }

  private RealmModel mockRealm() {
    return proxy(
        RealmModel.class,
        (proxy, method, args) -> {
          switch (method.getName()) {
            case "getAttribute":
              return realmAttributes.get((String) args[0]);
            case "getAttributes":
              return realmAttributes;
            case "getDefaultLocale":
              return realmDefaultLocale;
            case "getSmtpConfig":
              return smtpConfig;
            case "getDisplayName":
              return realmDisplayName;
            case "getName":
              return realmName;
            default:
              throw new UnsupportedOperationException("MockRealm: " + method.getName());
          }
        });
  }

  private KeycloakContext mockContext(RealmModel realm) {
    return proxy(
        KeycloakContext.class,
        (proxy, method, args) -> {
          if ("getRealm".equals(method.getName())) return realm;
          throw new UnsupportedOperationException("MockContext: " + method.getName());
        });
  }

  private ThemeManager mockThemeManager() {
    return proxy(
        ThemeManager.class,
        (proxy, method, args) -> {
          if ("getTheme".equals(method.getName())) return mockTheme();
          throw new UnsupportedOperationException("MockThemeManager: " + method.getName());
        });
  }

  private Theme mockTheme() {
    return proxy(
        Theme.class,
        (proxy, method, args) -> {
          switch (method.getName()) {
            case "getMessages":
              return loginThemeMessagesByLocale.getOrDefault(
                  ((Locale) args[0]).toLanguageTag(), new Properties());
            case "getEnhancedMessages":
              return loginThemeMessagesByLocale.getOrDefault(
                  ((Locale) args[1]).toLanguageTag(), new Properties());
            default:
              throw new UnsupportedOperationException("MockTheme: " + method.getName());
          }
        });
  }

  private HttpClientProvider mockHttpClientProvider() {
    return proxy(
        HttpClientProvider.class,
        (proxy, method, args) -> {
          if ("getHttpClient".equals(method.getName())) return httpClient;
          if ("getMaxConsumedResponseSize".equals(method.getName())) return Long.MAX_VALUE;
          throw new UnsupportedOperationException("MockHttpClientProvider: " + method.getName());
        });
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
    return (T)
        Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] {iface}, handler);
  }
}
