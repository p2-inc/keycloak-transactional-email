# keycloak-transactional-email — Agent Context

## What This Extension Does

Routes Keycloak email sends to external transactional email providers (SendGrid, Brevo, Mailtrap, etc.)
that support provider-managed **dynamic templates**. Instead of rendering a FreeMarker template into
HTML and sending it over SMTP, the extension passes the raw variable data (link, linkExpiration,
realmName, etc.) directly to the provider's template engine via API.

Routing is per-email-type and per-realm. Any email type without a configured template ID falls back
to Keycloak's standard FreeMarker + SMTP flow automatically.

## Architecture Overview

### Three-layer design

```
EmailTemplateProvider SPI (Keycloak)
  └─ TransactionalEmailTemplateProvider  [overrides FreeMarkerEmailTemplateProvider, order=1]
        │  checks realm attributes for provider + template ID
        │  if found → calls TransactionalEmailProvider
        │  if not   → super.send*() delegates to FreeMarker + SMTP
        └─ TransactionalEmailProvider SPI (custom)
              └─ SendGridEmailProvider    [provider id: "sendgrid"]
                    calls SendGrid v3 /mail/send API
```

### Key classes

| Class | Role |
|---|---|
| `TransactionalEmailTemplateProviderFactory` | Registered with Keycloak's `EmailTemplateSpi`, `order=1` beats the default FreeMarker provider |
| `TransactionalEmailTemplateProvider` | Extends `FreeMarkerEmailTemplateProvider`; overrides each typed `send*()` method plus the generic `send()` for third-party extension support |
| `TransactionalEmailSpi` | Registers the custom `transactional-email` SPI so Keycloak can discover provider factories |
| `SendGridEmailProviderFactory` | `@AutoService(TransactionalEmailProviderFactory.class)`, id=`"sendgrid"` |
| `SendGridEmailProvider` | HTTP call to SendGrid v3 using Keycloak's `SimpleHttp` (`org.keycloak.broker.provider.util`) |
| `TransactionalEmailResourceProviderFactory` | REST resource factory, id=`"ext-email-template"` |
| `TransactionalEmailResource` | JAX-RS resource at `/realms/{realm}/ext-email-template` |
| `KnownEmailTemplate` | Enum of all Keycloak email types + their available variables; drives the `/templates` endpoint |

### Adding a new provider

1. Create `YourProvider implements TransactionalEmailProvider` — implement `send()`
2. Create `YourProviderFactory implements TransactionalEmailProviderFactory`
   - `@AutoService(TransactionalEmailProviderFactory.class)`
   - `getId()` returns your provider ID (e.g. `"brevo"`)
3. Put the provider-specific config key constants in your provider class following the naming
   convention: `_providerConfig.ext-email-template.<providerId>.<key>`

No changes to the core `TransactionalEmailTemplateProvider` or the REST resource are needed.

## Realm Configuration

All configuration is stored as realm attributes under the `_providerConfig.ext-email-template.*`
namespace (consistent with Phase Two extension conventions).

```
_providerConfig.ext-email-template.provider
    → active provider ID, e.g. "sendgrid"
    → empty/absent = disabled, fall back to FreeMarker for all email types

_providerConfig.ext-email-template.template.<name>
    → provider-specific template ID for the named Keycloak email type
    → e.g. _providerConfig.ext-email-template.template.password-reset = "d-abc123"
    → absent = this email type falls back to FreeMarker

_providerConfig.ext-email-template.template.<name>.<locale>
    → optional locale-specific override, tried before the locale-less key above
    → e.g. _providerConfig.ext-email-template.template.password-reset.nl = "d-nl456"

    ONE LOCALE PER EMAIL. resolveTemplate() returns a ResolvedTemplate(templateId, locale) pair:
    the tier that selects the template also fixes the locale everything else in that email is
    rendered in (sender display name, eventDateFormatted, requiredActionsText). Tiers, each only
    eligible if a template is actually configured for it:
      1. template.<name>.<userLocale>        → recipient's own UserModel.LOCALE (set e.g. by the
                                               account console's language switcher)
      2. template.<name>.<userLanguage>      → language subtag of the above, if region-qualified
                                               (nl-NL → nl). Tried BEFORE the realm default:
                                               falling back within the recipient's own locale is
                                               more specific than giving up on it
      3. template.<name>.<realmDefaultLocale>
      4. template.<name>.<realmDefaultLang>  → same language-subtag fallback for the realm default
      5. template.<name>                     → renders in BASE_TEMPLATE_LOCALE (English), since
                                               the extension can't know what language the operator
                                               wrote that template in - a fixed convention
                                               matching Keycloak's no-suffix messages.properties
      6. nothing                             → FreeMarker + SMTP
    A locale with no template is skipped entirely rather than being allowed to win the formatting
    while losing the routing - otherwise a French body could arrive with German dates in it.
    NOT covered: linkExpirationFormatted, which formatExpiration() builds in English whatever the
    locale ("2 hours") - pre-existing upstream behaviour, left alone deliberately.

    Locale suffixes match case-insensitively on BOTH sides (stored user locale and configured key),
    via localizedAttribute(); underscores read as hyphens there too, so nl_NL and nl-NL are one
    key. The email type in the key still matches exactly, camel case included (executeActions).
    Blank values count as absent.

    → deliberately NOT resolved via LocaleSelectorProvider (which also considers the CURRENT
      request's cookie/Accept-Language/auth session) - for admin-triggered sends (e.g. "Send
      verification email" in the admin console) that reflects the ADMIN, not the recipient

_providerConfig.ext-email-template.smtp.fromDisplayName
_providerConfig.ext-email-template.smtp.fromDisplayName.<locale>
_providerConfig.ext-email-template.smtp.fromDisplayName.<name>
_providerConfig.ext-email-template.smtp.fromDisplayName.<name>.<locale>
    → optional sender display name overrides, resolved in resolveFromDisplayName()
    → e.g. ...fromDisplayName.nl = "Acme B.V.", ...fromDisplayName.org-invite = "Acme Invitations"

    Resolution order (most to least specific):
      1. smtp.fromDisplayName.<name>.<locale>
      2. smtp.fromDisplayName.<name>            (ignores locale - a deliberate per-type override)
      3. smtp.fromDisplayName.<locale>          (the "global brand per locale" case)
      4. smtp.fromDisplayName                   (unsuffixed - one name for everything THIS
                                                 extension routes; the realm's SMTP value still
                                                 applies to types that fall back to FreeMarker)
      5. realm's own SMTP "From display name" (Realm Settings → Email) - Keycloak's default,
         unchanged if none of the above are set
    <locale> here is the email's EFFECTIVE locale from ResolvedTemplate - the locale of the
    template that actually won, not a fresh lookup of the recipient's own. A send that fell
    through to the locale-less template therefore looks for fromDisplayName.en, not
    fromDisplayName.nl, even for a Dutch recipient.

    The sender ADDRESS has no equivalent override and always comes from the realm's own SMTP
    "from" setting - an address is tied to a verified sending domain, not to a language.

_providerConfig.ext-email-template.sendgrid.api-key
    → SendGrid API key (sensitive; masked as "**secret**" in GET responses)

_providerConfig.ext-email-template.sendgrid.api-url
    → optional override for the API endpoint; defaults to https://api.sendgrid.com/v3/mail/send
    → useful in tests to point at a mock server
```

## REST API

Base path: `/realms/{realm}/ext-email-template`

All endpoints require a valid Bearer token. GET requires `view-realm`; PUT/DELETE require `manage-realm`.

| Method | Path | Description |
|---|---|---|
| `GET` | `/config` | Return current config; sensitive values masked. Returns **404** if no config has been set. |
| `PUT` | `/config` | Save config; values equal to `**secret**` are not overwritten. Returns **400** if `provider` is set to an unknown provider ID. |
| `DELETE` | `/config` | Remove all `_providerConfig.ext-email-template.*` attributes |
| `GET` | `/templates` | List Keycloak built-in email types with their available variables |

## Template Variables

Every template receives base variables automatically:
`realmName`, `userEmail`, `userFirstName`, `userLastName`, `username`

Per-type additional variables are listed in `KnownEmailTemplate` enum and returned by `GET /templates`.

Notable special cases:
- `email-update-confirmation` sends to the **new** (unverified) email address, not `user.getEmail()`
- `sendSmtpTestEmail` is intentionally **not** overridden — it tests SMTP connectivity directly
- `email-verification-with-code` provides a `code` variable instead of a `link`
- Event templates (`event-*`) receive `eventDate`, `eventIpAddress`, plus all event detail key/values

### Third-party extension support

The generic `send(subjectKey, subjectAttributes, template, bodyAttributes)` from
`FreeMarkerEmailTemplateProvider` is also overridden. This means emails sent by other Phase Two
extensions (e.g. magic-link's `magic-link-email`, keycloak-orgs' `invitation-email`) are
automatically routed through the transactional provider if a matching template mapping is configured.
String, numeric, and boolean values from `bodyAttributes` are forwarded as template data; complex
objects (e.g. `ProfileBean`) are filtered out.

To route a magic-link email:
```json
{ "templates": { "magic-link-email": "d-your-template-id" } }
```
- All templates with a `link` also receive `linkExpirationFormatted` — a pre-formatted human-readable
  string (e.g. `"30 minutes"`, `"2 hours"`) derived from the raw `linkExpiration` minutes value.
  Use `{{linkExpirationFormatted}}` in templates rather than formatting the raw number yourself.

### SendGrid template syntax

SendGrid **dynamic** transactional templates require Handlebars double-brace syntax:
```
{{realmName}}  {{link}}  {{linkExpirationFormatted}}
```
Single-brace `{variable}` is the legacy substitution syntax and will not be replaced in dynamic
templates. Also note variable names must match exactly — `{{realmName}}` not `{{realm}}`.

## Phase Two Conventions Used

- `@AutoService` annotation for zero-config SPI discovery (no manual META-INF/services)
- `@JBossLog` for logging (Lombok)
- `_providerConfig.ext-email-template.*` realm attribute namespace
- `AbstractAdminResource` + `BaseRealmResourceProvider` + `CorsResource` pattern (copied from
  keycloak-magic-link) for admin REST resources
- `representation` package for JAX-RS DTO classes (consistent with keycloak-orgs, keycloak-events,
  keycloak-magic-link) — not `model`
- `SimpleHttp` (`org.keycloak.broker.provider.util.SimpleHttp`) for outbound HTTP, not `java.net.http`
- `quay.io/phasetwo/keycloak-crdb:{version}` container image in integration tests
- `testcontainers-keycloak` + REST-Assured for integration test harness

## Build

```bash
mvn verify          # compile + unit tests (no container needed for SendGridEmailProviderTest)
mvn verify -Pit     # integration tests require Docker
```

The integration tests (`TransactionalEmailResourceTest`) start a real Keycloak container. The unit
test (`SendGridEmailProviderTest`) uses an in-process JDK `HttpServer` mock — no container required.

## Local Dev Environment

```bash
make dev   # builds the JAR and starts docker compose
```

On startup:
- A `dev` realm is imported from `docker/dev-realm.json` with Mailhog SMTP pre-configured,
  `sslRequired=none`, a `testuser` account, and placeholder SendGrid config already set.
- A `keycloak-config` sidecar container runs once after Keycloak is healthy; it patches
  the `master` realm (SMTP, sslRequired, admin email) and updates `testuser`'s email in `dev`.

**Configure your email addresses** — copy `.env.example` or create `.env` at the repo root:
```
KEYCLOAK_ADMIN_EMAIL=you@example.com
TEST_USER_EMAIL=you@example.com
```
These are gitignored. Docker Compose picks them up automatically.

**Services:**
- Admin UI: `http://localhost:8080/admin` (admin / admin)
- Mailhog UI: `http://localhost:8025` — catches all SMTP fallback emails

**Triggering a test email end-to-end:**
1. Go to `http://localhost:8080/realms/dev/account` → Sign in → Forgot Password
2. Enter `TEST_USER_EMAIL` — this fires `email-verification` through SendGrid
3. Or use the Admin API: `PUT /admin/realms/dev/users/{id}/execute-actions-email` with `["VERIFY_EMAIL"]`

**Updating the SendGrid template ID or API key** — edit `docker/dev-realm.json` attributes or
call the config API directly after startup:
```bash
TOKEN=$(curl -s -X POST "http://localhost:8080/realms/master/protocol/openid-connect/token" \
  -d "client_id=admin-cli&grant_type=password&username=admin&password=admin" \
  | jq -r .access_token)

curl -X PUT "http://localhost:8080/realms/dev/ext-email-template/config" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "provider": "sendgrid",
    "templates": { "email-verification": "d-your-real-template-id" },
    "providerConfig": { "sendgrid.api-key": "SG.your-real-key" }
  }'
```

**Inspect available template variables:**
```bash
curl "http://localhost:8080/realms/dev/ext-email-template/templates" \
  -H "Authorization: Bearer $TOKEN"
```

## Future UI

The UI for this extension lives in [phasetwo-keycloak-admin-ui](../phasetwo-keycloak-admin-ui)
in `src/admin/realm-settings/EmailTab.tsx`. The extension itself deliberately ships no UI — see
other Phase Two extensions (keycloak-magic-link, keycloak-orgs) for the same pattern.

## Sibling Projects

- `keycloak` — Phase Two's Keycloak fork; email SPI interfaces are in `server-spi-private`
- `bridge-extensions` — other Phase Two extensions; keycloak-magic-link and keycloak-events have
  the most relevant patterns for this project
- `phasetwo-keycloak-admin-ui` — Keycloakify-based admin UI theme where the EmailTab UI lives
