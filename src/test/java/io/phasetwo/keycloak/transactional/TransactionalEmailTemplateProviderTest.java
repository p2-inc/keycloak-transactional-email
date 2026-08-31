package io.phasetwo.keycloak.transactional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import io.phasetwo.keycloak.transactional.spi.TransactionalEmailProvider;
import io.phasetwo.keycloak.transactional.template.TransactionalEmailTemplateProvider;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;

/**
 * Unit tests for {@link TransactionalEmailTemplateProvider}'s locale-aware template routing. No
 * Keycloak container required - a fake {@link TransactionalEmailProvider} records which template ID
 * it was asked to send with.
 */
class TransactionalEmailTemplateProviderTest {

  private static final String PROVIDER_KEY = "_providerConfig.ext-email-template.provider";
  private static final String TEMPLATE_PREFIX = "_providerConfig.ext-email-template.template.";
  private static final String FROM_NAME_PREFIX =
      "_providerConfig.ext-email-template.smtp.fromDisplayName.";

  /** Records the templateId and vars passed to send(), so tests can assert on routing decisions. */
  private static class RecordingProvider implements TransactionalEmailProvider {
    final AtomicReference<String> lastTemplateId = new AtomicReference<>();
    final AtomicReference<Map<String, Object>> lastVars = new AtomicReference<>();
    final AtomicReference<String> lastFromEmail = new AtomicReference<>();
    final AtomicReference<String> lastFromName = new AtomicReference<>();

    @Override
    public void send(
        String templateId,
        Map<String, Object> templateData,
        String toEmail,
        String toName,
        String fromEmail,
        String fromName) {
      lastTemplateId.set(templateId);
      lastVars.set(templateData);
      lastFromEmail.set(fromEmail);
      lastFromName.set(fromName);
    }

    @Override
    public void close() {}
  }

  private RecordingProvider recordingProvider;
  private MockKeycloakSession mock;

  private TransactionalEmailTemplateProvider buildProvider() {
    recordingProvider = new RecordingProvider();
    mock = new MockKeycloakSession();
    mock.setAttribute(PROVIDER_KEY, "fake");
    mock.registerProvider(TransactionalEmailProvider.class, "fake", recordingProvider);

    KeycloakSession session = mock.asSession();
    TransactionalEmailTemplateProvider templateProvider =
        new TransactionalEmailTemplateProvider(session);
    templateProvider.setRealm(session.getContext().getRealm()).setUser(mock.asUser());
    return templateProvider;
  }

  @Test
  void usesLocaleSpecificTemplate_whenUserHasLocaleAttributeAndMappingExists() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.nl", "nl-template");
    mock.setUserAttribute(UserModel.LOCALE, "nl");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastTemplateId.get(), is("nl-template"));
  }

  @Test
  void fallsBackToPlainTemplate_whenUserLocaleHasNoMapping() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    // No password-reset.de mapping configured.
    mock.setUserAttribute(UserModel.LOCALE, "de");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastTemplateId.get(), is("base-template"));
  }

  @Test
  void fallsBackToRealmDefaultLocale_whenUserHasNoLocaleAttribute() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.fr", "fr-template");
    mock.setRealmDefaultLocale("fr");
    // No user LOCALE attribute set at all.

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastTemplateId.get(), is("fr-template"));
  }

  @Test
  void userLocaleTakesPriorityOverRealmDefaultLocale() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.nl", "nl-template");
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.fr", "fr-template");
    mock.setRealmDefaultLocale("fr");
    mock.setUserAttribute(UserModel.LOCALE, "nl");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastTemplateId.get(), is("nl-template"));
  }

  @Test
  void localeLookupIsCaseInsensitive_onTheStoredUserLocale() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.nl", "nl-template");
    mock.setUserAttribute(UserModel.LOCALE, "NL");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastTemplateId.get(), is("nl-template"));
  }

  @Test
  void localeLookupIsCaseInsensitive_onTheConfiguredAttributeKey() throws Exception {
    // The other direction: a lowercase user locale must still find an attribute key whose locale
    // suffix was typed in uppercase. Only the suffix is case-insensitive - the email type in the
    // key (here "password-reset") still matches exactly.
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.NL", "nl-template");
    mock.setUserAttribute(UserModel.LOCALE, "nl");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastTemplateId.get(), is("nl-template"));
  }

  @Test
  void underscoredStoredLocale_matchesTheHyphenatedAttributeKey() throws Exception {
    // A locale stored Java-style (nl_NL, as an IdP attribute mapper might write it) has to resolve
    // the same as the BCP-47 nl-NL. Left as-is it matches no key at all, and further down the line
    // Locale.forLanguageTag turns it into ROOT, formatting the email in no language in particular.
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.nl-NL", "nl-template");
    mock.setUserAttribute(UserModel.LOCALE, "nl_NL");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastTemplateId.get(), is("nl-template"));
  }

  @Test
  void regionalStoredLocale_fallsBackToTheLanguageOnlyTemplate() throws Exception {
    // Operators configure one template per language, but recipients routinely carry a
    // region-qualified locale (an IdP attribute mapper writing nl_NL, a browser negotiating
    // en-GB). Without a language-subtag fallback, nl-NL would skip a perfectly good .nl template
    // and land on the base one.
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.nl", "nl-template");
    mock.setUserAttribute(UserModel.LOCALE, "nl-NL");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastTemplateId.get(), is("nl-template"));
  }

  @Test
  void regionalTemplate_winsOverTheLanguageOnlyTemplate() throws Exception {
    // The fallback only applies when the region-qualified key is absent: a realm that went to the
    // trouble of configuring .nl-NL gets it, not the broader .nl.
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.nl", "nl-template");
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.nl-NL", "nl-nl-template");
    mock.setUserAttribute(UserModel.LOCALE, "nl-NL");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastTemplateId.get(), is("nl-nl-template"));
  }

  @Test
  void usersLanguageFallback_winsOverTheRealmDefaultLocale() throws Exception {
    // Ordering check: falling back within the recipient's own locale (nl-NL -> nl) is still more
    // specific than giving up on them and using the realm default, so a Dutch recipient in an
    // English-default realm gets Dutch rather than English.
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.nl", "nl-template");
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.en", "en-template");
    mock.setRealmDefaultLocale("en");
    mock.setUserAttribute(UserModel.LOCALE, "nl-NL");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastTemplateId.get(), is("nl-template"));
  }

  @Test
  void languageFallback_alsoFixesTheFormattingLocale() throws Exception {
    // The winning tier is the .nl one, so the whole email renders in nl - not in the recipient's
    // own nl-NL, and not in ROOT. Asserted through the message bundle the action names come from.
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "executeActions.nl", "nl-execute-actions-template");
    mock.setLoginThemeMessage("nl", "updatePasswordTitle", "Wachtwoord bijwerken");
    mock.setUserAttribute(UserModel.LOCALE, "nl_NL");
    templateProvider.setAttribute(
        org.keycloak.models.Constants.TEMPLATE_ATTR_REQUIRED_ACTIONS,
        java.util.List.of("UPDATE_PASSWORD"));

    templateProvider.sendExecuteActions("https://example.com/actions", 60);

    assertThat(recordingProvider.lastTemplateId.get(), is("nl-execute-actions-template"));
    assertThat(recordingProvider.lastVars.get().get("requiredActionsText"), is("Wachtwoord bijwerken"));
  }

  @Test
  void blankLocaleMapping_fallsThroughToBaseTemplate() throws Exception {
    // An attribute cleared to "" is treated as absent rather than as a configured empty template.
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.nl", "");
    mock.setUserAttribute(UserModel.LOCALE, "nl");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastTemplateId.get(), is("base-template"));
  }

  @Test
  void noLocaleMappingAndNoBaseMapping_fallsBackToFreeMarker() throws Exception {
    // No template mapping at all for password-reset - super.sendPasswordReset() would be called,
    // which needs a real FreeMarker/theme stack this mock doesn't provide. Assert indirectly: the
    // transactional provider is never invoked.
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setUserAttribute(UserModel.LOCALE, "nl");

    try {
      templateProvider.sendPasswordReset("https://example.com/reset", 60);
    } catch (Exception expected) {
      // Falling through to real FreeMarker rendering fails in this minimal mock (no theme
      // stack) - that's fine, we only care that it didn't route through our fake provider.
    }

    assertThat(recordingProvider.lastTemplateId.get(), is(nullValue()));
  }

  @Test
  void sendEvent_populatesEventDateFormatted() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "event-login_error", "login-error-template");
    mock.setUserAttribute(UserModel.LOCALE, "en");

    Event event = new Event();
    event.setType(EventType.LOGIN_ERROR);
    event.setTime(1710504000000L); // 2024-03-15T12:00:00Z
    event.setIpAddress("203.0.113.5");

    templateProvider.sendEvent(event);

    Map<String, Object> vars = recordingProvider.lastVars.get();
    assertThat(vars.get("eventDate"), is(1710504000000L));
    assertThat(vars.get("eventDateFormatted"), instanceOf(String.class));
    String formatted = (String) vars.get("eventDateFormatted");
    assertThat(formatted.isBlank(), is(false));
    // Proves it's a human-readable rendering, not the raw epoch value passed through as-is.
    assertThat(formatted, not(is(String.valueOf(1710504000000L))));
  }

  @Test
  void eventDateFormatted_reflectsTheSelectedTemplateLocale() throws Exception {
    Event event = new Event();
    event.setType(EventType.LOGIN_ERROR);
    event.setTime(1710504000000L);

    TransactionalEmailTemplateProvider enProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "event-login_error", "login-error-template");
    mock.setUserAttribute(UserModel.LOCALE, "en");
    enProvider.sendEvent(event);
    String enFormatted = (String) recordingProvider.lastVars.get().get("eventDateFormatted");

    TransactionalEmailTemplateProvider nlProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "event-login_error", "login-error-template");
    mock.setAttribute(TEMPLATE_PREFIX + "event-login_error.nl", "nl-login-error-template");
    mock.setUserAttribute(UserModel.LOCALE, "nl");
    nlProvider.sendEvent(event);
    String nlFormatted = (String) recordingProvider.lastVars.get().get("eventDateFormatted");

    assertThat(nlFormatted, not(is(enFormatted)));
  }

  @Test
  void sendExecuteActions_localizesRequiredActionsFromLoginThemeBundle() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "executeActions", "execute-actions-template");
    // Mirrors Keycloak's own base login theme messages_en.properties keys/values.
    mock.setLoginThemeMessage("en", "updatePasswordTitle", "Update password");
    mock.setLoginThemeMessage("en", "loginTotpTitle", "Mobile Authenticator Setup");
    templateProvider.setAttribute(
        org.keycloak.models.Constants.TEMPLATE_ATTR_REQUIRED_ACTIONS,
        java.util.List.of("UPDATE_PASSWORD", "CONFIGURE_TOTP"));

    templateProvider.sendExecuteActions("https://example.com/actions", 60);

    Map<String, Object> vars = recordingProvider.lastVars.get();
    assertThat(vars.get("requiredActionsText"), is("Update password, Mobile Authenticator Setup"));
  }

  @Test
  void sendExecuteActions_localizesRequiredActionsToTheSelectedTemplateLocale() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "executeActions", "execute-actions-template");
    mock.setAttribute(TEMPLATE_PREFIX + "executeActions.nl", "nl-execute-actions-template");
    mock.setLoginThemeMessage("en", "updatePasswordTitle", "Update password");
    mock.setLoginThemeMessage("nl", "updatePasswordTitle", "Wachtwoord bijwerken");
    mock.setUserAttribute(UserModel.LOCALE, "nl");
    templateProvider.setAttribute(
        org.keycloak.models.Constants.TEMPLATE_ATTR_REQUIRED_ACTIONS,
        java.util.List.of("UPDATE_PASSWORD"));

    templateProvider.sendExecuteActions("https://example.com/actions", 60);

    Map<String, Object> vars = recordingProvider.lastVars.get();
    assertThat(vars.get("requiredActionsText"), is("Wachtwoord bijwerken"));
  }

  @Test
  void sendExecuteActions_humanizesUnknownRequiredAction() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "executeActions", "execute-actions-template");
    templateProvider.setAttribute(
        org.keycloak.models.Constants.TEMPLATE_ATTR_REQUIRED_ACTIONS,
        java.util.List.of("some_custom_action"));

    templateProvider.sendExecuteActions("https://example.com/actions", 60);

    Map<String, Object> vars = recordingProvider.lastVars.get();
    assertThat(vars.get("requiredActionsText"), is("Some Custom Action"));
  }

  @Test
  void sendExecuteActions_fallsBackToHumanizedName_whenThemeHasNoMatchingKey() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "executeActions", "execute-actions-template");
    // UPDATE_PASSWORD is a known action, but no theme message was configured for it here.
    templateProvider.setAttribute(
        org.keycloak.models.Constants.TEMPLATE_ATTR_REQUIRED_ACTIONS,
        java.util.List.of("UPDATE_PASSWORD"));

    templateProvider.sendExecuteActions("https://example.com/actions", 60);

    Map<String, Object> vars = recordingProvider.lastVars.get();
    assertThat(vars.get("requiredActionsText"), is("Update Password"));
  }

  @Test
  void sendExecuteActions_noRequiredActionsAttribute_producesEmptyText() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "executeActions", "execute-actions-template");

    templateProvider.sendExecuteActions("https://example.com/actions", 60);

    Map<String, Object> vars = recordingProvider.lastVars.get();
    assertThat(vars.get("requiredActionsText"), is(""));
  }

  @Test
  void eventDateFormat_dmyPreset() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "event-login_error", "login-error-template");
    mock.setAttribute("_providerConfig.ext-email-template.event-date-format", "dmy");

    Event event = new Event();
    event.setType(EventType.LOGIN_ERROR);
    event.setTime(1710504000000L); // 2024-03-15T12:00:00Z

    templateProvider.sendEvent(event);

    String formatted = (String) recordingProvider.lastVars.get().get("eventDateFormatted");
    assertThat(formatted, matchesPattern("\\d{2}-\\d{2}-\\d{4} \\d{2}:\\d{2}"));
  }

  @Test
  void eventDateFormat_ymdPreset() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "event-login_error", "login-error-template");
    mock.setAttribute("_providerConfig.ext-email-template.event-date-format", "YMD"); // case-insensitive

    Event event = new Event();
    event.setType(EventType.LOGIN_ERROR);
    event.setTime(1710504000000L);

    templateProvider.sendEvent(event);

    String formatted = (String) recordingProvider.lastVars.get().get("eventDateFormatted");
    assertThat(formatted, matchesPattern("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"));
  }

  @Test
  void eventDateFormat_customPattern() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "event-login_error", "login-error-template");
    mock.setAttribute("_providerConfig.ext-email-template.event-date-format", "yyyy/MM/dd");

    Event event = new Event();
    event.setType(EventType.LOGIN_ERROR);
    event.setTime(1710504000000L);

    templateProvider.sendEvent(event);

    String formatted = (String) recordingProvider.lastVars.get().get("eventDateFormatted");
    assertThat(formatted, matchesPattern("\\d{4}/\\d{2}/\\d{2}"));
  }

  @Test
  void eventDateFormat_invalidPattern_fallsBackToLocaleAwareDefault() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "event-login_error", "login-error-template");
    mock.setAttribute("_providerConfig.ext-email-template.event-date-format", "not a valid pattern {{{");

    Event event = new Event();
    event.setType(EventType.LOGIN_ERROR);
    event.setTime(1710504000000L);

    templateProvider.sendEvent(event);

    // Doesn't throw, and still produces a non-blank formatted string.
    String formatted = (String) recordingProvider.lastVars.get().get("eventDateFormatted");
    assertThat(formatted.isBlank(), is(false));
  }

  @Test
  void usesLocaleSpecificFromDisplayName_whenMappingExistsAndUserLocaleMatches() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.nl", "nl-template");
    mock.setSmtpConfig("fromDisplayName", "Keycloak");
    mock.setAttribute(FROM_NAME_PREFIX + "nl", "Acme B.V.");
    mock.setUserAttribute(UserModel.LOCALE, "nl");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastFromName.get(), is("Acme B.V."));
  }

  @Test
  void fallsBackToSmtpConfigFromDisplayName_whenNoLocaleMappingExists() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    mock.setSmtpConfig("fromDisplayName", "Keycloak");
    // No smtp.fromDisplayName.de mapping configured.
    mock.setUserAttribute(UserModel.LOCALE, "de");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastFromName.get(), is("Keycloak"));
  }

  @Test
  void userLocaleTakesPriorityOverRealmDefaultLocale_forFromDisplayName() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.nl", "nl-template");
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.fr", "fr-template");
    mock.setSmtpConfig("fromDisplayName", "Keycloak");
    mock.setAttribute(FROM_NAME_PREFIX + "nl", "Acme B.V.");
    mock.setAttribute(FROM_NAME_PREFIX + "fr", "Acme France");
    mock.setRealmDefaultLocale("fr");
    mock.setUserAttribute(UserModel.LOCALE, "nl");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastFromName.get(), is("Acme B.V."));
  }

  @Test
  void fromDisplayNameLocaleLookupIsCaseInsensitive() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.nl", "nl-template");
    // Uppercase on both sides that matter: the stored user locale and the configured key suffix.
    mock.setAttribute(FROM_NAME_PREFIX + "NL", "Acme B.V.");
    mock.setUserAttribute(UserModel.LOCALE, "NL");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastFromName.get(), is("Acme B.V."));
  }

  @Test
  void fromEmail_alwaysUsesSmtpConfig_regardlessOfLocaleOrTemplateAttributes() throws Exception {
    // The sender address has no override mechanism at all, unlike fromDisplayName - it always
    // comes from the realm's own SMTP config, since it's tied to a verified sending domain and
    // doesn't need to vary the way a brand name does. Setting an unrelated fromDisplayName
    // attribute here proves the two fields are resolved independently.
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    mock.setSmtpConfig("from", "noreply@example.com");
    mock.setAttribute(FROM_NAME_PREFIX + "nl", "Acme B.V.");
    mock.setUserAttribute(UserModel.LOCALE, "nl");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastFromEmail.get(), is("noreply@example.com"));
  }

  @Test
  void fromDisplayName_perTemplateOverride_appliesRegardlessOfLocale() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    mock.setSmtpConfig("fromDisplayName", "Keycloak");
    mock.setAttribute(FROM_NAME_PREFIX + "password-reset", "Acme Security Team");
    // No global smtp.fromDisplayName.de mapping - proves this isn't falling through to tier 3.
    mock.setUserAttribute(UserModel.LOCALE, "de");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastFromName.get(), is("Acme Security Team"));
  }

  @Test
  void fromDisplayName_perTemplateOverride_winsOverGlobalPerLocale() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    // The nl template matters: without it the effective locale is English, the tier-3 nl mapping
    // is never eligible, and this test would pass whichever way round the two tiers are tried.
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.nl", "nl-template");
    mock.setAttribute(FROM_NAME_PREFIX + "nl", "Acme B.V."); // global, tier 3
    mock.setAttribute(FROM_NAME_PREFIX + "password-reset", "Acme Security Team"); // tier 2
    mock.setUserAttribute(UserModel.LOCALE, "nl");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    // The per-template override is a deliberate choice by whoever configured it, so it wins
    // even though a global per-locale mapping also matches.
    assertThat(recordingProvider.lastFromName.get(), is("Acme Security Team"));
  }

  @Test
  void fromDisplayName_perTemplatePerLocaleOverride_winsOverPerTemplateOnly() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.nl", "nl-template");
    mock.setAttribute(FROM_NAME_PREFIX + "password-reset", "Acme Security Team"); // tier 2
    mock.setAttribute(FROM_NAME_PREFIX + "password-reset.nl", "Acme Beveiligingsteam"); // tier 1
    mock.setUserAttribute(UserModel.LOCALE, "nl");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastFromName.get(), is("Acme Beveiligingsteam"));
  }

  @Test
  void fromDisplayName_globalOverride_appliesToEveryTypeAndLocale() throws Exception {
    // The unsuffixed key: the form an operator is most likely to try first, having seen the three
    // suffixed ones. It overrides the realm's SMTP value for provider-routed emails.
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    mock.setSmtpConfig("fromDisplayName", "Keycloak");
    mock.setAttribute("_providerConfig.ext-email-template.smtp.fromDisplayName", "Acme");
    mock.setUserAttribute(UserModel.LOCALE, "de");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastFromName.get(), is("Acme"));
  }

  @Test
  void fromDisplayName_globalPerLocaleOverride_winsOverUnsuffixedGlobal() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.nl", "nl-template");
    mock.setAttribute("_providerConfig.ext-email-template.smtp.fromDisplayName", "Acme"); // tier 4
    mock.setAttribute(FROM_NAME_PREFIX + "nl", "Acme B.V."); // tier 3
    mock.setUserAttribute(UserModel.LOCALE, "nl");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastFromName.get(), is("Acme B.V."));
  }

  @Test
  void fromDisplayName_perTemplateOverride_doesNotLeakToOtherEmailTypes() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.nl", "nl-template");
    mock.setAttribute(FROM_NAME_PREFIX + "org-invite", "Acme Invitations");
    mock.setAttribute(FROM_NAME_PREFIX + "nl", "Acme B.V."); // global fallback
    mock.setUserAttribute(UserModel.LOCALE, "nl");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    // password-reset has no override of its own, so it falls through to the global mapping -
    // the org-invite-specific override must not apply here.
    assertThat(recordingProvider.lastFromName.get(), is("Acme B.V."));
  }

  @Test
  void formattingLocaleFollowsTheSelectedTemplate_notTheUsersOwnLocale() throws Exception {
    // The case that motivated resolving template and locale as one decision: the recipient's own
    // locale (de) has no template, so the realm default (fr) tier wins the routing. The variables
    // injected into that French body must be French too - never German.
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "executeActions.fr", "fr-execute-actions-template");
    mock.setLoginThemeMessage("fr", "updatePasswordTitle", "Mettre a jour le mot de passe");
    mock.setLoginThemeMessage("de", "updatePasswordTitle", "Passwort aktualisieren");
    mock.setRealmDefaultLocale("fr");
    mock.setUserAttribute(UserModel.LOCALE, "de");
    templateProvider.setAttribute(
        org.keycloak.models.Constants.TEMPLATE_ATTR_REQUIRED_ACTIONS,
        java.util.List.of("UPDATE_PASSWORD"));

    templateProvider.sendExecuteActions("https://example.com/actions", 60);

    assertThat(recordingProvider.lastTemplateId.get(), is("fr-execute-actions-template"));
    assertThat(
        recordingProvider.lastVars.get().get("requiredActionsText"),
        is("Mettre a jour le mot de passe"));
  }

  @Test
  void baseTemplateFormatsInEnglish_notTheUsersOwnLocale() throws Exception {
    // The locale-less mapping carries no language information, so it renders in the documented
    // base locale (English, matching Keycloak's no-suffix message bundle) rather than reaching
    // back to the recipient's locale and mixing Dutch strings into an unknown-language template.
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "executeActions", "base-execute-actions-template");
    mock.setLoginThemeMessage("en", "updatePasswordTitle", "Update password");
    mock.setLoginThemeMessage("nl", "updatePasswordTitle", "Wachtwoord bijwerken");
    mock.setUserAttribute(UserModel.LOCALE, "nl");
    templateProvider.setAttribute(
        org.keycloak.models.Constants.TEMPLATE_ATTR_REQUIRED_ACTIONS,
        java.util.List.of("UPDATE_PASSWORD"));

    templateProvider.sendExecuteActions("https://example.com/actions", 60);

    assertThat(recordingProvider.lastVars.get().get("requiredActionsText"), is("Update password"));
  }

  @Test
  void fromDisplayNameFollowsTheSelectedTemplateLocale_notTheUsersOwnLocale() throws Exception {
    // Same single-locale rule for the sender name: only the base template exists, so the email is
    // English throughout and the Dutch sender override is not eligible, even for a Dutch user.
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    mock.setSmtpConfig("fromDisplayName", "Keycloak");
    mock.setAttribute(FROM_NAME_PREFIX + "nl", "Acme B.V.");
    mock.setUserAttribute(UserModel.LOCALE, "nl");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastFromName.get(), is("Keycloak"));
  }
}
