package io.phasetwo.keycloak.transactional.template;

import io.phasetwo.keycloak.transactional.spi.TransactionalEmailProvider;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.email.EmailException;
import org.keycloak.email.freemarker.FreeMarkerEmailTemplateProvider;
import org.keycloak.events.Event;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.UserModel;
import org.keycloak.theme.Theme;

/**
 * Extends {@link FreeMarkerEmailTemplateProvider} to intercept email sends and route them to a
 * configured {@link TransactionalEmailProvider} when a matching template mapping exists in the
 * realm configuration.
 *
 * <p>Per-template routing is controlled by realm attributes:
 *
 * <ul>
 *   <li>{@code _providerConfig.ext-email-template.provider} — the provider ID to use (e.g. {@code
 *       "sendgrid"})
 *   <li>{@code _providerConfig.ext-email-template.template.<name>} — the provider-specific template
 *       ID for a given Keycloak email type
 *   <li>{@code _providerConfig.ext-email-template.template.<name>.<locale>} — an optional
 *       locale-specific override, tried before the locale-less key above (see {@link
 *       #resolveTemplate(String)})
 *   <li>{@code _providerConfig.ext-email-template.smtp.fromDisplayName} — an optional sender
 *       display name for every email this extension routes (see {@link #resolveFromDisplayName})
 *   <li>{@code _providerConfig.ext-email-template.smtp.fromDisplayName.<locale>} — the same, but
 *       only for emails in this locale, applied across every email type
 *   <li>{@code _providerConfig.ext-email-template.smtp.fromDisplayName.<name>} — an optional
 *       sender display name for one specific email type, regardless of locale
 *   <li>{@code _providerConfig.ext-email-template.smtp.fromDisplayName.<name>.<locale>} — the most
 *       specific override: this email type, this locale. Tried before all of the above, which
 *       are in turn tried before the realm's own SMTP "From display name" setting
 * </ul>
 *
 * <p>Every email is rendered in a single effective locale, settled once by {@link
 * #resolveTemplate(String)} and carried by {@link ResolvedTemplate}: the tier that selects the
 * template body also fixes the locale used for the sender display name, {@code eventDateFormatted}
 * and {@code requiredActionsText}. Resolving those independently of the routing decision would
 * allow, say, a French template body to be filled in with German dates.
 *
 * <p>The sender address itself (as opposed to its display name) is deliberately not overridable
 * this way - it always comes from the realm's own SMTP "From" setting. Unlike the display name,
 * an address is tied to a verified sending domain, so realistically it doesn't vary by locale or
 * email type the way a brand name does.
 *
 * <p>If no provider is configured, or no template mapping exists for the current email type, the
 * call falls back to FreeMarker + SMTP.
 */
@JBossLog
public class TransactionalEmailTemplateProvider extends FreeMarkerEmailTemplateProvider {

  public static final String CONFIG_PREFIX = "_providerConfig.ext-email-template";
  public static final String PROVIDER_KEY = CONFIG_PREFIX + ".provider";
  public static final String TEMPLATE_KEY_PREFIX = CONFIG_PREFIX + ".template.";
  public static final String EVENT_DATE_FORMAT_KEY = CONFIG_PREFIX + ".event-date-format";
  public static final String SMTP_FROM_DISPLAY_NAME_KEY =
      CONFIG_PREFIX + ".smtp.fromDisplayName";
  public static final String SMTP_FROM_DISPLAY_NAME_KEY_PREFIX =
      SMTP_FROM_DISPLAY_NAME_KEY + ".";

  /**
   * Named presets for {@link #EVENT_DATE_FORMAT_KEY}, as {@link java.time.format.DateTimeFormatter}
   * patterns. Any configured value that isn't one of these (case-insensitive) or {@code "auto"} is
   * treated as a literal {@code DateTimeFormatter} pattern instead, so fully custom formats are
   * supported too - not just these three presets.
   */
  private static final Map<String, String> EVENT_DATE_FORMAT_PRESETS =
      Map.of(
          "dmy", "dd-MM-yyyy HH:mm",
          "mdy", "MM/dd/yyyy hh:mm a",
          "ymd", "yyyy-MM-dd HH:mm");

  public TransactionalEmailTemplateProvider(KeycloakSession session) {
    super(session);
  }

  @Override
  public void sendPasswordReset(String link, long expirationInMinutes) throws EmailException {
    Optional<ResolvedTemplate> resolved = resolveTemplate("password-reset");
    if (resolved.isPresent()) {
      Map<String, Object> vars = baseVariables();
      vars.put("link", link);
      vars.put("linkExpiration", expirationInMinutes);
      vars.put("linkExpirationFormatted", formatExpiration(expirationInMinutes));
      sendViaProvider("password-reset", resolved.get(), vars, null);
    } else {
      super.sendPasswordReset(link, expirationInMinutes);
    }
  }

  @Override
  public void sendVerifyEmail(String link, long expirationInMinutes) throws EmailException {
    Optional<ResolvedTemplate> resolved = resolveTemplate("email-verification");
    if (resolved.isPresent()) {
      Map<String, Object> vars = baseVariables();
      vars.put("link", link);
      vars.put("linkExpiration", expirationInMinutes);
      vars.put("linkExpirationFormatted", formatExpiration(expirationInMinutes));
      sendViaProvider("email-verification", resolved.get(), vars, null);
    } else {
      super.sendVerifyEmail(link, expirationInMinutes);
    }
  }

  @Override
  public void sendExecuteActions(String link, long expirationInMinutes) throws EmailException {
    Optional<ResolvedTemplate> resolved = resolveTemplate("executeActions");
    if (resolved.isPresent()) {
      Map<String, Object> vars = baseVariables();
      vars.put("link", link);
      vars.put("linkExpiration", expirationInMinutes);
      vars.put("linkExpirationFormatted", formatExpiration(expirationInMinutes));
      vars.put("requiredActionsText", buildRequiredActionsText(resolved.get().locale()));
      sendViaProvider("executeActions", resolved.get(), vars, null);
    } else {
      super.sendExecuteActions(link, expirationInMinutes);
    }
  }

  @Override
  public void sendEmailUpdateConfirmation(String link, long expirationInMinutes, String newEmail)
      throws EmailException {
    Optional<ResolvedTemplate> resolved = resolveTemplate("email-update-confirmation");
    if (resolved.isPresent()) {
      Map<String, Object> vars = baseVariables();
      vars.put("link", link);
      vars.put("linkExpiration", expirationInMinutes);
      vars.put("linkExpirationFormatted", formatExpiration(expirationInMinutes));
      vars.put("newEmail", newEmail);
      // Send to the new (unconfirmed) address, matching FreeMarker behaviour
      sendViaProvider("email-update-confirmation", resolved.get(), vars, newEmail);
    } else {
      super.sendEmailUpdateConfirmation(link, expirationInMinutes, newEmail);
    }
  }

  @Override
  public void sendConfirmIdentityBrokerLink(String link, long expirationInMinutes)
      throws EmailException {
    Optional<ResolvedTemplate> resolved = resolveTemplate("identity-provider-link");
    if (resolved.isPresent()) {
      Map<String, Object> vars = baseVariables();
      vars.put("link", link);
      vars.put("linkExpiration", expirationInMinutes);
      vars.put("linkExpirationFormatted", formatExpiration(expirationInMinutes));

      Object brokerCtx = attributes.get(IDENTITY_PROVIDER_BROKER_CONTEXT);
      if (brokerCtx instanceof org.keycloak.broker.provider.BrokeredIdentityContext ctx) {
        String displayName = ctx.getIdpConfig().getDisplayName();
        if (displayName == null || displayName.isBlank()) {
          displayName =
              org.keycloak.common.util.ObjectUtil.capitalize(ctx.getIdpConfig().getAlias());
        }
        vars.put("identityProviderDisplayName", displayName);
        vars.put("identityProviderAlias", ctx.getIdpConfig().getAlias());
      }

      sendViaProvider("identity-provider-link", resolved.get(), vars, null);
    } else {
      super.sendConfirmIdentityBrokerLink(link, expirationInMinutes);
    }
  }

  @Override
  public void sendOrgInviteEmail(
      OrganizationModel organization, String link, long expirationInMinutes) throws EmailException {
    Optional<ResolvedTemplate> resolved = resolveTemplate("org-invite");
    if (resolved.isPresent()) {
      Map<String, Object> vars = baseVariables();
      vars.put("link", link);
      vars.put("linkExpiration", expirationInMinutes);
      vars.put("linkExpirationFormatted", formatExpiration(expirationInMinutes));
      vars.put("organizationName", organization.getName());
      if (user.getFirstName() != null) vars.put("firstName", user.getFirstName());
      if (user.getLastName() != null) vars.put("lastName", user.getLastName());
      sendViaProvider("org-invite", resolved.get(), vars, null);
    } else {
      super.sendOrgInviteEmail(organization, link, expirationInMinutes);
    }
  }

  @Override
  public void sendEvent(Event event) throws EmailException {
    String ftlName = "event-" + event.getType().toString().toLowerCase();
    Optional<ResolvedTemplate> resolved = resolveTemplate(ftlName);
    if (resolved.isPresent()) {
      Map<String, Object> vars = baseVariables();
      vars.put("eventDate", event.getTime());
      vars.put("eventDateFormatted", formatEventDate(event.getTime(), resolved.get().locale()));
      vars.put("eventIpAddress", event.getIpAddress());
      if (event.getDetails() != null) {
        vars.put("credentialType", event.getDetails().get("credential_type"));
        vars.putAll(event.getDetails());
      }
      sendViaProvider(ftlName, resolved.get(), vars, null);
    } else {
      super.sendEvent(event);
    }
  }

  @Override
  public void send(
      String subjectKey,
      List<Object> subjectAttributes,
      String template,
      Map<String, Object> bodyAttributes)
      throws EmailException {
    // Strip .ftl suffix if present — template names in realm config are stored without it
    String templateName = template.endsWith(".ftl") ? template.substring(0, template.length() - 4) : template;
    Optional<ResolvedTemplate> resolved = resolveTemplate(templateName);
    if (resolved.isPresent()) {
      Map<String, Object> vars = baseVariables();
      if (bodyAttributes != null) {
        bodyAttributes.forEach((k, v) -> {
          if (v instanceof String || v instanceof Number || v instanceof Boolean) {
            vars.put(k, v);
          }
        });
      }
      sendViaProvider(templateName, resolved.get(), vars, null);
    } else {
      super.send(subjectKey, subjectAttributes, template, bodyAttributes);
    }
  }

  // sendSmtpTestEmail is intentionally not overridden — it tests the SMTP connection directly

  // ---- helpers ----

  /**
   * The outcome of resolving an email to a provider template: the template ID to send with, and
   * the locale that same email's variables are rendered in.
   *
   * <p>These are deliberately one value rather than two independent lookups. The locale here is
   * whichever tier of {@link #resolveTemplate(String)} actually won - so the body, the sender
   * display name, {@code eventDateFormatted} and {@code requiredActionsText} are all rendered in
   * the language of the template that was actually selected. Resolving them separately would let
   * a French template body arrive with German dates in it.
   */
  private record ResolvedTemplate(String templateId, Locale locale) {}

  /**
   * Locale assumed for the locale-less {@code template.<name>} mapping. The extension cannot know
   * what language an operator wrote that template in, so it fixes a convention - English, matching
   * Keycloak's own no-suffix {@code messages.properties} bundle - rather than reaching back to the
   * recipient's locale, which would reintroduce exactly the body/variable mismatch {@link
   * ResolvedTemplate} exists to prevent.
   */
  private static final Locale BASE_TEMPLATE_LOCALE = Locale.ENGLISH;

  /**
   * Resolves both the template ID for {@code templateName} and the locale the resulting email is
   * rendered in, as a single decision. Tiers, most specific first - each is only eligible if a
   * template is actually configured for it:
   *
   * <ol>
   *   <li>{@code template.<name>.<userLocale>} - the recipient's own stored profile locale ({@link
   *       UserModel#LOCALE}, the same attribute set by the account console's language switcher);
   *       everything renders in that locale
   *   <li>{@code template.<name>.<realmDefaultLocale>} - the realm's configured default locale;
   *       everything renders in that locale
   *   <li>{@code template.<name>} - the locale-less mapping; everything renders in {@link
   *       #BASE_TEMPLATE_LOCALE}
   *   <li>no mapping at all - empty, and the caller falls back to FreeMarker + SMTP
   * </ol>
   *
   * <p>The winning tier picks the template <em>and</em> the formatting locale together, so an
   * email whose body comes from the French template cannot carry German dates: a tier the
   * recipient has no template for is simply not eligible, rather than being allowed to win the
   * formatting while losing the routing.
   *
   * <p>Deliberately does NOT use {@link org.keycloak.locale.LocaleSelectorProvider}: it also
   * factors in the CURRENT HTTP request's locale cookie / Accept-Language header / auth session,
   * which for admin-triggered sends (e.g. "Send verification email" in the admin console, or the
   * {@code execute-actions-email} admin REST endpoint) reflects whoever is performing the action,
   * not the recipient the email is actually going to - the opposite of what per-recipient template
   * routing needs. Reading the recipient's own stored attribute directly is what stays correct
   * regardless of who/what triggered the send.
   */
  private Optional<ResolvedTemplate> resolveTemplate(String templateName) {
    if (realm == null) return Optional.empty();
    String provider = realm.getAttribute(PROVIDER_KEY);
    if (provider == null || provider.isBlank()) {
      log.debugf("No transactional provider configured for %s; falling back to FreeMarker", templateName);
      return Optional.empty();
    }

    for (String locale : candidateLocales()) {
      String localized = localizedAttribute(TEMPLATE_KEY_PREFIX + templateName, locale);
      if (localized != null) {
        log.debugf("Using locale '%s' template mapping for %s", locale, templateName);
        return Optional.of(new ResolvedTemplate(localized, Locale.forLanguageTag(locale)));
      }
    }

    String templateId = realm.getAttribute(TEMPLATE_KEY_PREFIX + templateName);
    if (templateId == null || templateId.isBlank()) {
      log.debugf(
          "No template mapping for %s under provider '%s'; falling back to FreeMarker",
          templateName, provider);
      return Optional.empty();
    }
    return Optional.of(new ResolvedTemplate(templateId, BASE_TEMPLATE_LOCALE));
  }

  /**
   * Resolves the sender display name, trying four configured tiers from most to least specific,
   * before the realm's own setting:
   *
   * <ol>
   *   <li>{@code smtp.fromDisplayName.<templateName>.<locale>} - this email type, this locale
   *   <li>{@code smtp.fromDisplayName.<templateName>} - this email type, any locale (a deliberate
   *       override that intentionally ignores locale, e.g. a fixed sender name for {@code
   *       org-invite} regardless of language)
   *   <li>{@code smtp.fromDisplayName.<locale>} - any email type, this locale (the "global" brand
   *       override, e.g. Acme B.V. for nl vs Acme Inc. for everything else)
   *   <li>{@code smtp.fromDisplayName} - any email type, any locale: one name for everything this
   *       extension sends. Distinct from the realm setting below in that it applies only to
   *       provider-routed emails, leaving the SMTP value in place for the types that still fall
   *       back to FreeMarker. It is also the key an operator is most likely to reach for first,
   *       having seen the three suffixed forms, so it resolves rather than silently doing nothing.
   *   <li>the realm's own {@link org.keycloak.models.RealmModel#getSmtpConfig()} "fromDisplayName"
   *       value - unchanged Keycloak behavior, used when none of the above are configured
   * </ol>
   *
   * <p>{@code locale} is the email's effective locale from {@link ResolvedTemplate}, not a fresh
   * lookup of the recipient's own locale - the sender name is part of the same rendered email as
   * the body, so a send that fell through to the base English template uses the English sender
   * name rather than the recipient's, for the same reason the dates in it are English.
   *
   * <p>The sender address itself deliberately has no equivalent override - see the class-level
   * docs.
   *
   * @param templateName the Keycloak email type being sent (e.g. {@code "password-reset"}), for
   *     the per-template tiers
   * @param locale the email's effective locale, for the per-locale tiers
   * @param smtpConfig the realm's SMTP config map, used as the final fallback
   */
  private String resolveFromDisplayName(
      String templateName, Locale locale, Map<String, String> smtpConfig) {
    String localeTag = locale.toLanguageTag();

    String perTemplatePerLocale =
        localizedAttribute(SMTP_FROM_DISPLAY_NAME_KEY_PREFIX + templateName, localeTag);
    if (perTemplatePerLocale != null) {
      log.debugf(
          "Using per-template locale '%s' override for from display name (%s)",
          localeTag, templateName);
      return perTemplatePerLocale;
    }

    String perTemplate = realm.getAttribute(SMTP_FROM_DISPLAY_NAME_KEY_PREFIX + templateName);
    if (perTemplate != null && !perTemplate.isBlank()) {
      log.debugf("Using per-template override for from display name (%s)", templateName);
      return perTemplate;
    }

    String perLocale = localizedAttribute(SMTP_FROM_DISPLAY_NAME_KEY, localeTag);
    if (perLocale != null) {
      log.debugf("Using locale '%s' override for from display name", localeTag);
      return perLocale;
    }

    String global = realm.getAttribute(SMTP_FROM_DISPLAY_NAME_KEY);
    if (global != null && !global.isBlank()) {
      log.debug("Using global override for from display name");
      return global;
    }

    return smtpConfig.getOrDefault("fromDisplayName", "");
  }

  /**
   * Reads the realm attribute {@code <prefix>.<locale>}, matching the locale suffix
   * case-insensitively, so a key configured as {@code ...password-reset.NL} is found for a
   * recipient locale of {@code nl} and vice versa - locales are conventionally lowercase, but
   * nothing stops an operator typing the region-style casing.
   *
   * <p>Underscores in the suffix are read as hyphens on both sides too, so {@code nl_NL} and
   * {@code nl-NL} are one key rather than two.
   *
   * <p>Only the locale suffix is loosened this way; the prefix (which contains the email type,
   * e.g. the camel-cased {@code executeActions}) still matches exactly. Blank values are treated
   * as absent, so an attribute cleared to "" falls through to the next tier rather than winning
   * it.
   *
   * @return the configured value, or {@code null} if no key matches or the value is blank
   */
  private String localizedAttribute(String prefix, String locale) {
    String exact = realm.getAttribute(prefix + "." + locale);
    if (exact != null && !exact.isBlank()) return exact;

    Map<String, String> attributes = realm.getAttributes();
    if (attributes == null) return null;

    int suffixStart = prefix.length() + 1;
    for (Map.Entry<String, String> entry : attributes.entrySet()) {
      String key = entry.getKey();
      if (key.length() > suffixStart
          && key.startsWith(prefix)
          && key.charAt(prefix.length()) == '.'
          && key.substring(suffixStart).replace('_', '-').equalsIgnoreCase(locale)
          && entry.getValue() != null
          && !entry.getValue().isBlank()) {
        return entry.getValue();
      }
    }
    return null;
  }

  /**
   * The locales eligible to select a template, most specific first: the recipient's own stored
   * locale, then the realm's default. Only used by {@link #resolveTemplate(String)} - everything
   * else takes the locale that resolution actually settled on, rather than re-deriving its own.
   *
   * <p>Each of those two contributes its language subtag as a further candidate, immediately after
   * itself: a recipient stored as {@code nl-NL} tries {@code template.<name>.nl-NL} and then
   * {@code template.<name>.nl}. Region-qualified locales reach Keycloak routinely (an IdP
   * attribute mapper writing {@code nl_NL}, a browser negotiating {@code en-GB}), while operators
   * configure one template per <em>language</em> - without this, such a recipient would skip a
   * perfectly good {@code .nl} template and land on the base one.
   *
   * <p>The recipient's own language subtag is tried before the realm default, not after it:
   * falling back within the recipient's own locale is still more specific than giving up on them
   * entirely. So a {@code nl-NL} recipient in an {@code en}-default realm with {@code .nl} and
   * {@code .en} templates gets Dutch.
   */
  private List<String> candidateLocales() {
    List<String> locales = new ArrayList<>();
    if (user != null) addWithLanguageFallback(locales, user.getFirstAttribute(UserModel.LOCALE));
    if (realm != null) addWithLanguageFallback(locales, realm.getDefaultLocale());
    return locales;
  }

  /** Appends {@code raw} and then its language subtag, skipping blanks and anything already there. */
  private static void addWithLanguageFallback(List<String> locales, String raw) {
    String locale = normalizeLocale(raw);
    if (locale == null) return;
    if (!locales.contains(locale)) locales.add(locale);

    int separator = locale.indexOf('-');
    if (separator <= 0) return;
    String language = locale.substring(0, separator);
    if (!locales.contains(language)) locales.add(language);
  }

  private static String normalizeLocale(String locale) {
    if (locale == null || locale.isBlank()) return null;
    // Underscores as well as case: a locale stored Java-style (nl_NL, as an IdP attribute mapper
    // might write it) has to resolve the same as the BCP-47 nl-NL. Left as-is it matches no key
    // at all, and Locale.forLanguageTag turns it into ROOT further down the line.
    return locale.trim().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  private TransactionalEmailProvider resolveProvider() {
    String providerId = realm.getAttribute(PROVIDER_KEY);
    return session.getProvider(TransactionalEmailProvider.class, providerId);
  }

  private Map<String, Object> baseVariables() {
    Map<String, Object> vars = new HashMap<>();
    vars.put("realmName", getRealmName());
    if (user != null) {
      vars.put("userEmail", user.getEmail());
      vars.put("userFirstName", user.getFirstName());
      vars.put("userLastName", user.getLastName());
      vars.put("username", user.getUsername());
    }
    return vars;
  }

  private static String formatExpiration(long minutes) {
    if (minutes % 60 == 0) {
      long hours = minutes / 60;
      return hours + " " + (hours == 1 ? "hour" : "hours");
    }
    return minutes + " " + (minutes == 1 ? "minute" : "minutes");
  }

  /**
   * Known required-action provider IDs, mapped to the message key Keycloak's own base {@code
   * login} theme uses for that action's page title (see {@code
   * theme/base/login/messages/messages_*.properties} in the Keycloak repo) - reusing these gets
   * every language Keycloak itself ships translations for, for free, including realm-level
   * localization overrides (via {@link #buildRequiredActionsText}'s use of {@code
   * getEnhancedMessages}), rather than a second, hand-maintained, English-only translation of the
   * same handful of strings. Deliberately NOT the {@code email} theme's own bundle - it has no
   * equivalent keys; these are login-flow page titles.
   *
   * <p>Actions with no entry here (rare/custom ones, e.g. {@code delete_account}) fall back to
   * {@link #humanizeRequiredAction}, which is English-only - there is no general-purpose "action
   * ID to localized name" bundle in Keycloak for those.
   */
  private static final Map<String, String> REQUIRED_ACTION_MESSAGE_KEYS =
      Map.ofEntries(
          Map.entry("UPDATE_PASSWORD", "updatePasswordTitle"),
          Map.entry("VERIFY_EMAIL", "emailVerifyTitle"),
          Map.entry("UPDATE_PROFILE", "loginProfileTitle"),
          Map.entry("CONFIGURE_TOTP", "loginTotpTitle"),
          Map.entry("TERMS_AND_CONDITIONS", "termsTitle"),
          Map.entry("webauthn-register", "webauthn-registration-title"),
          Map.entry("webauthn-register-passwordless", "webauthn-registration-title"));

  /**
   * Builds the comma-separated {@code requiredActionsText} variable for the {@code executeActions}
   * email from the required-action IDs Keycloak attaches via {@code setAttribute(Constants
   * .TEMPLATE_ATTR_REQUIRED_ACTIONS, ...)} before calling {@link #sendExecuteActions} (see {@code
   * UserResource#executeActionsEmail} - this is not something the base {@link
   * FreeMarkerEmailTemplateProvider} exposes as a template variable itself, so it has to be read
   * directly off the inherited {@code attributes} map, the same way {@link
   * #sendConfirmIdentityBrokerLink} already reads {@code IDENTITY_PROVIDER_BROKER_CONTEXT}).
   *
   * <p>Each action name is localized via Keycloak's own login theme message bundle, not a
   * hand-rolled English map - see {@link #REQUIRED_ACTION_MESSAGE_KEYS}.
   *
   * @param locale the email's effective locale from {@link ResolvedTemplate} - the same language
   *     as the template body these names are injected into
   */
  @SuppressWarnings("unchecked")
  private String buildRequiredActionsText(Locale locale) {
    Object raw = attributes.get(Constants.TEMPLATE_ATTR_REQUIRED_ACTIONS);
    if (!(raw instanceof List)) return "";

    Properties messages = loginThemeMessages(locale);

    return ((List<Object>) raw)
        .stream()
            .map(String::valueOf)
            .map(actionId -> localizeRequiredAction(actionId, messages))
            .collect(Collectors.joining(", "));
  }

  private Properties loginThemeMessages(Locale locale) {
    // Broad catch deliberate: resolving a display-name translation must never be the reason an
    // actual email fails to send - degrade to humanizeRequiredAction's English fallback instead.
    try {
      Theme theme = session.theme().getTheme(Theme.Type.LOGIN);
      return realm != null ? theme.getEnhancedMessages(realm, locale) : theme.getMessages(locale);
    } catch (Exception e) {
      log.warnf("Could not load login theme messages for locale '%s': %s", locale, e.getMessage());
      return new Properties();
    }
  }

  private static String localizeRequiredAction(String actionId, Properties messages) {
    String messageKey = REQUIRED_ACTION_MESSAGE_KEYS.get(actionId);
    if (messageKey != null) {
      String localized = messages.getProperty(messageKey);
      if (localized != null && !localized.isBlank()) return localized;
    }
    return humanizeRequiredAction(actionId);
  }

  // English-only last resort for required actions with no entry in REQUIRED_ACTION_MESSAGE_KEYS
  // (rare/custom ones) or whose bundle lookup failed - e.g. "SOME_CUSTOM_ACTION" -> "Some Custom
  // Action". Better than showing the raw provider ID, but not a substitute for real localization.
  private static String humanizeRequiredAction(String actionId) {
    String[] words = actionId.replace('-', '_').split("_+");
    return Arrays.stream(words)
        .filter(w -> !w.isBlank())
        .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase(Locale.ROOT))
        .collect(Collectors.joining(" "));
  }

  /**
   * Formats an event timestamp for display, rather than exposing the raw millisecond epoch value
   * ({@code eventDate}) that {@link Event#getTime()} returns. Formatted in the server's local
   * timezone - Keycloak does not store a per-user timezone.
   *
   * <p>The format itself is controlled by the {@link #EVENT_DATE_FORMAT_KEY} realm attribute:
   *
   * <ul>
   *   <li>unset, blank, or {@code "auto"} - locale-aware default ({@link FormatStyle#MEDIUM})
   *   <li>{@code "dmy"} / {@code "mdy"} / {@code "ymd"} - one of {@link #EVENT_DATE_FORMAT_PRESETS}
   *   <li>anything else - used directly as a {@link DateTimeFormatter} pattern, so realms that need
   *       something the presets don't cover can supply their own (e.g. {@code "EEEE d MMMM yyyy"}).
   *       An invalid pattern falls back to the locale-aware default rather than failing the send.
   * </ul>
   *
   * @param locale the email's effective locale from {@link ResolvedTemplate} - the same language
   *     as the template body this date is injected into
   */
  private String formatEventDate(long timeMillis, Locale locale) {
    Instant instant = Instant.ofEpochMilli(timeMillis);
    ZoneId zone = ZoneId.systemDefault();

    String configured = realm != null ? realm.getAttribute(EVENT_DATE_FORMAT_KEY) : null;
    if (configured != null && !configured.isBlank() && !"auto".equalsIgnoreCase(configured)) {
      String pattern =
          EVENT_DATE_FORMAT_PRESETS.getOrDefault(configured.toLowerCase(Locale.ROOT), configured);
      try {
        return DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(instant);
      } catch (IllegalArgumentException e) {
        log.warnf(
            "Invalid %s value '%s' (%s); falling back to the locale-aware default format",
            EVENT_DATE_FORMAT_KEY, configured, e.getMessage());
      }
    }

    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(locale)
        .withZone(zone)
        .format(instant);
  }

  /**
   * Resolves the active provider and dispatches the send call. Falls back to FreeMarker if the
   * provider is not available at runtime (e.g. factory JAR not installed).
   *
   * @param templateName the Keycloak email type being sent (e.g. {@code "password-reset"}) - used
   *     only to resolve the per-template sender-identity tiers in {@link
   *     #resolveFromDisplayName}, distinct from the provider-specific template identifier carried
   *     by {@code resolved}
   * @param resolved the template ID and effective locale settled on by {@link
   *     #resolveTemplate(String)}
   */
  private void sendViaProvider(
      String templateName,
      ResolvedTemplate resolved,
      Map<String, Object> vars,
      String overrideToEmail)
      throws EmailException {
    TransactionalEmailProvider provider = resolveProvider();
    if (provider == null) {
      log.warnf(
          "TransactionalEmailProvider '%s' not found; falling back to FreeMarker",
          realm.getAttribute(PROVIDER_KEY));
      return;
    }

    String toEmail = overrideToEmail != null ? overrideToEmail : user.getEmail();
    String toName =
        Stream.of(user.getFirstName(), user.getLastName())
            .filter(s -> s != null && !s.isBlank())
            .collect(Collectors.joining(" "));

    Map<String, String> smtpConfig = realm.getSmtpConfig();
    String fromEmail = smtpConfig.getOrDefault("from", "");
    String fromName = resolveFromDisplayName(templateName, resolved.locale(), smtpConfig);

    log.infof(
        "Sending email via transactional provider '%s' (templateId=%s, to=%s)",
        realm.getAttribute(PROVIDER_KEY), resolved.templateId(), toEmail);

    try {
      provider.send(resolved.templateId(), vars, toEmail, toName, fromEmail, fromName);
    } catch (Exception e) {
      throw new EmailException("Transactional email provider failed to send", e);
    }
  }
}
