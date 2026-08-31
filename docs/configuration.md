# Configuration

All configuration is stored as realm attributes. There are two ways to manage them: this extension's REST API, or the Admin Console when you run the Phase Two Keycloak image - see [Setting attributes](#setting-attributes) below.

---

## Realm attribute keys

| Attribute key                                                 | Description                                                                                                                                |
| ------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `_providerConfig.ext-email-template.provider`                 | Active provider ID (`sendgrid`, `brevo`, `postmark`, `mailtrap`, `mailgun`, `customerio`, `resend`, `awsses`). Empty or absent = disabled. |
| `_providerConfig.ext-email-template.template.<name>`          | Provider template ID for a given email type (e.g. `template.password-reset`).                                                              |
| `_providerConfig.ext-email-template.event-date-format`        | Format for `eventDateFormatted` on event-notification emails (see [Event date formatting](#event-date-formatting)). Unset = locale-aware default. |
| `_providerConfig.ext-email-template.smtp.fromDisplayName` (optionally suffixed `.<locale>`, `.<name>`, or `.<name>.<locale>`) | Sender display name: global / global-per-locale / per-template / per-template-per-locale (see [Locale-specific sender identity](#locale-specific-sender-identity)). Unset = realm's SMTP "From display name" for every email. |
| `_providerConfig.ext-email-template.sendgrid.api-key`         | SendGrid API key                                                                                                                           |
| `_providerConfig.ext-email-template.sendgrid.api-url`         | SendGrid API URL override (default: `https://api.sendgrid.com/v3/mail/send`)                                                               |
| `_providerConfig.ext-email-template.brevo.api-key`            | Brevo API key                                                                                                                              |
| `_providerConfig.ext-email-template.brevo.api-url`            | Brevo API URL override                                                                                                                     |
| `_providerConfig.ext-email-template.postmark.server-token`    | Postmark server token                                                                                                                      |
| `_providerConfig.ext-email-template.postmark.api-url`         | Postmark API URL override                                                                                                                  |
| `_providerConfig.ext-email-template.mailtrap.api-token`       | Mailtrap API token                                                                                                                         |
| `_providerConfig.ext-email-template.mailtrap.api-url`         | Mailtrap API URL override                                                                                                                  |
| `_providerConfig.ext-email-template.mailgun.api-key`          | Mailgun API key                                                                                                                            |
| `_providerConfig.ext-email-template.mailgun.domain`           | Mailgun sending domain                                                                                                                     |
| `_providerConfig.ext-email-template.mailgun.api-url`          | Mailgun API URL override                                                                                                                   |
| `_providerConfig.ext-email-template.customerio.api-key`       | Customer.io API key                                                                                                                        |
| `_providerConfig.ext-email-template.customerio.api-url`       | Customer.io API URL override                                                                                                               |
| `_providerConfig.ext-email-template.resend.api-key`           | Resend API key                                                                                                                             |
| `_providerConfig.ext-email-template.resend.api-url`           | Resend API URL override                                                                                                                    |
| `_providerConfig.ext-email-template.awsses.access-key-id`     | AWS access key ID                                                                                                                          |
| `_providerConfig.ext-email-template.awsses.secret-access-key` | AWS secret access key                                                                                                                      |
| `_providerConfig.ext-email-template.awsses.region`            | AWS region (e.g. `us-east-1`)                                                                                                              |
| `_providerConfig.ext-email-template.awsses.api-url`           | AWS SES API URL override                                                                                                                   |

### Template names

Substitute `<name>` in `_providerConfig.ext-email-template.template.<name>` with one of the following. Any name not listed here is not intercepted and falls back to Keycloak's standard FreeMarker + SMTP flow.

| `<name>`                                   | Trigger                            |
| ------------------------------------------ | ---------------------------------- |
| `password-reset`                           | Forgot-password flow               |
| `email-verification`                       | Registration / admin verify        |
| `executeActions`                           | Admin-triggered required actions   |
| `email-update-confirmation`                | User changes their email address   |
| `email-verification-with-code`             | OTP-style verification             |
| `identity-provider-link`                   | Account linking to IdP             |
| `org-invite`                               | Organization invitation            |
| `event-login_error`                        | Failed login notification          |
| `event-update_password`                    | Password changed notification      |
| `event-remove_totp`                        | TOTP device removed                |
| `event-update_totp`                        | TOTP device updated                |
| `event-remove_credential`                  | Credential removed                 |
| `event-update_credential`                  | Credential updated                 |
| `event-user_disabled_by_temporary_lockout` | Temporary lockout                  |
| `event-user_disabled_by_permanent_lockout` | Permanent lockout                  |
| `magic-link-email`                         | `keycloak-magic-link` extension    |
| `invitation-email`                         | `keycloak-orgs` extension          |

See [template-variables.md](template-variables.md) for the variables each template receives.

---

## Locale-specific templates

Each email type can have per-locale template overrides, tried before the locale-less mapping:

```
_providerConfig.ext-email-template.template.<name>.<locale>
```

For example, alongside a default `template.password-reset`, you can add:

```
_providerConfig.ext-email-template.template.password-reset.nl = d-nl-template-id
_providerConfig.ext-email-template.template.password-reset.fr = d-fr-template-id
```

### One locale per email

Each email resolves to a single **effective locale**, and every locale-dependent value this extension resolves is rendered in it: the template body, the sender display name, `eventDateFormatted` and `requiredActionsText`. A tier only wins if a template is actually configured for it, so the tier that picks the body also fixes the language of what gets injected into it - a French body can never arrive with German dates in it.

One exception, inherited from Keycloak rather than introduced here: `linkExpirationFormatted` is built in English (`2 hours`) whatever the effective locale is. Use `linkExpiration`, the raw number of minutes, if you want to phrase that yourself in the template.

Resolution, most specific first:

| # | Tier | Template | Everything else rendered in |
| - | ---- | -------- | --------------------------- |
| 1 | Recipient's own stored profile locale (`UserModel.LOCALE` - the same attribute set by the account console's language switcher) | `template.<name>.<userLocale>` | that locale |
| 2 | The language of that locale, if it was region-qualified | `template.<name>.<userLanguage>` | that language |
| 3 | The realm's configured default locale | `template.<name>.<realmDefaultLocale>` | that locale |
| 4 | The language of the realm default, if it was region-qualified | `template.<name>.<realmDefaultLanguage>` | that language |
| 5 | The locale-less mapping | `template.<name>` | **English** (see below) |
| 6 | Nothing configured | - | standard FreeMarker + SMTP, as without this extension |

A locale whose template isn't configured is skipped entirely - it can't win the formatting while losing the routing.

**Region-qualified locales** fall back to their language before the realm default is considered, so a recipient stored as `nl-NL` tries `template.password-reset.nl-NL`, then `template.password-reset.nl`, and only then the realm default. Recipients carry region-qualified locales routinely - an identity-provider attribute mapper writing `nl_NL`, a browser negotiating `en-GB` - while templates are normally configured one per language, so without this they would skip a perfectly good `.nl` template. Falling back within the recipient's own locale is treated as more specific than giving up on it, so a `nl-NL` recipient in an `en`-default realm with both `.nl` and `.en` templates gets Dutch. Configure `.nl-NL` explicitly if you want to distinguish it from `.nl`; the more specific key always wins.

**Why English for the locale-less tier:** the extension can't know what language you wrote that template in, so it fixes a convention rather than guessing - English, matching Keycloak's own no-suffix `messages.properties` bundle. If a recipient should get Dutch dates and action names, give them a Dutch template (`template.<name>.nl`); that's what makes `nl` the email's effective locale.

Locale matching is case-insensitive (`nl` and `NL` are equivalent), and underscores are read as hyphens (`nl_NL` and `nl-NL` are the same key), on both the stored locale and the configured attribute key.

This is intentionally **not** resolved via Keycloak's `LocaleSelectorProvider` (used for login-page/theme locale selection), because that also factors in the *current HTTP request's* cookie, `Accept-Language` header, and active authentication session. For sends triggered by the recipient's own browser (e.g. self-service forgot-password) that happens to line up with the recipient, but for admin-triggered sends (e.g. "Send verification email" in the Admin Console, or the `execute-actions-email` admin REST endpoint) it would reflect the *admin's* locale, not the recipient's - the opposite of what per-recipient routing needs. Reading the recipient's own stored attribute directly stays correct regardless of who or what triggered the send.

---

## Locale-specific sender identity

Keycloak's realm-wide SMTP setting (**Realm Settings → Email → From display name**) is a single fixed value with no notion of locale or email type, so even with per-locale templates every email a realm sends shows the same sender name. These optional attributes override it, most specific first:

```
_providerConfig.ext-email-template.smtp.fromDisplayName.<name>.<locale>   # this type + this locale
_providerConfig.ext-email-template.smtp.fromDisplayName.<name>            # this type, any locale
_providerConfig.ext-email-template.smtp.fromDisplayName.<locale>          # any type, this locale
_providerConfig.ext-email-template.smtp.fromDisplayName                   # any type, any locale
```

Anything none of them match falls back to the realm's own "From display name", so a realm with none of these set behaves exactly as before.

The unsuffixed key differs from the realm's SMTP setting in scope, not in effect: it renames the sender only on emails this extension routes to a provider, leaving the SMTP value in place for the types that still fall back to FreeMarker.

```
_providerConfig.ext-email-template.smtp.fromDisplayName.nl = Acme B.V.
_providerConfig.ext-email-template.smtp.fromDisplayName.en = Acme Inc.
_providerConfig.ext-email-template.smtp.fromDisplayName.org-invite = Acme Invitations
```

Here every email type uses the sender name matching the locale, except `org-invite`, which uses its own in any language: the per-template tier wins over the global per-locale one for the type it names, and affects no other type.

`<name>` is the same email-type name used in [template routing](#template-names). `<locale>` is the email's [effective locale](#one-locale-per-email) - the locale of the template that was selected, not a separate lookup of the recipient's own. A send that fell through to the locale-less template therefore looks for `smtp.fromDisplayName.en`, not `.nl`, even for a Dutch recipient; configure that language's template to change that.

The sender **address** has no equivalent override - it always comes from the realm's own SMTP "From" setting, since an address is tied to a verified sending domain rather than to a language.

---

## Event date formatting

Event-notification emails (`event-login_error`, `event-update_password`, etc. - see [template-variables.md](template-variables.md#event-notification-emails)) receive `eventDate` as a raw Unix millisecond timestamp, which isn't fit to put directly in a template. `eventDateFormatted` provides a human-readable rendering instead, controlled by the `_providerConfig.ext-email-template.event-date-format` realm attribute:

| Value                  | Result                                                    |
| ----------------------- | ---------------------------------------------------------- |
| unset, blank, or `auto` | Locale-aware default (`DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)`), in the email's [effective locale](#one-locale-per-email) |
| `dmy`                   | `dd-MM-yyyy HH:mm` (e.g. `27-07-2026 15:49`)                |
| `mdy`                   | `MM/dd/yyyy hh:mm a` (e.g. `07/27/2026 03:49 PM`)           |
| `ymd`                   | `yyyy-MM-dd HH:mm` (e.g. `2026-07-27 15:49`)                |
| anything else           | Used directly as a [`DateTimeFormatter` pattern](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/format/DateTimeFormatter.html) - fully custom formats are supported, not just the three presets above (e.g. `EEEE d MMMM yyyy`) |

An invalid custom pattern falls back to the locale-aware default rather than failing the send (logged as a warning). Formatted in the server's local timezone - Keycloak does not store a per-user timezone.

The format string is realm-wide; only the locale it is rendered with varies per email, and it is the email's [effective locale](#one-locale-per-email) - the same one the template body was selected for.

---

## Setting attributes

### Option 1: the REST API

Works on any Keycloak image. Use this extension's own REST resource (see [REST API](#rest-api) below), or the Keycloak Admin REST API directly (`PUT /admin/realms/{realm}` with an `attributes` map).

**Example: configure SendGrid for password reset**

```bash
curl -X PUT "$KC/realms/myrealm/ext-email-template/config" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "provider": "sendgrid",
    "templates": { "password-reset": "d-your-template-id" },
    "providerConfig": { "sendgrid.api-key": "SG.your-api-key" }
  }'
```

Repeat the `templates` entry for any additional email types you want to route, using the template names from the [template variables reference](template-variables.md).

### Option 2: the Admin Console

The `phasetwo-ui` admin theme, shipped in the [Phase Two Keycloak image](https://github.com/p2-inc/keycloak), adds a page for editing realm attributes, so the whole configuration can be done from the Admin Console without touching the API:

1. Open the Admin Console and select your realm.
2. If the realm isn't already using it, set **Realm Settings → Themes → Admin theme** to `phasetwo-ui`.
3. Go to **Realm Settings** → the **Attributes** tab.
4. Use **Add attribute** to create each key-value pair.

**Example: configure SendGrid for password reset**

| Key                                                          | Value                |
| ------------------------------------------------------------ | -------------------- |
| `_providerConfig.ext-email-template.provider`                | `sendgrid`           |
| `_providerConfig.ext-email-template.sendgrid.api-key`        | `SG.your-api-key`    |
| `_providerConfig.ext-email-template.template.password-reset` | `d-your-template-id` |

Repeat the last row for any additional email types, and add the locale-suffixed keys from [locale-specific templates](#locale-specific-templates) the same way.

Stock Keycloak's admin console has no generic editor for realm-level attributes ([keycloak/keycloak#17732](https://github.com/keycloak/keycloak/issues/17732)), so on a vanilla image use option 1. Either way, don't confuse this tab with **Realm Settings → User profile → Attributes**, which manages the *user profile schema* (`username`, `email`, `locale`, etc.) - an unrelated feature that won't show or edit any `_providerConfig.ext-email-template.*` values.

### Disabling

To disable the extension without removing individual template mappings, set the provider to an empty value or delete it.

---

## REST API

Base path: `/realms/{realm}/ext-email-template`

Requires a valid admin Bearer token. `GET` endpoints need `view-realm`; `PUT`/`DELETE` need `manage-realm`.

### `GET /config`

Returns the current configuration. Sensitive values (API keys) are masked as `**secret**`. Returns **404** if no configuration has been set.

```bash
curl "$KC/realms/myrealm/ext-email-template/config" \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "provider": "sendgrid",
  "templates": {
    "password-reset": "d-abc123abc123",
    "email-verification": "d-def456def456"
  },
  "providerConfig": {
    "sendgrid.api-key": "**secret**"
  }
}
```

### `PUT /config`

Saves configuration. Values equal to `**secret**` are silently ignored, so a round-tripped GET response can be PUT back without clearing stored API keys. Returns **400** if `provider` is set to an unrecognised value.

```bash
curl -X PUT "$KC/realms/myrealm/ext-email-template/config" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "provider": "sendgrid",
    "templates": {
      "password-reset": "d-abc123abc123",
      "email-verification": "d-def456def456",
      "org-invite": "d-ghi789ghi789"
    },
    "providerConfig": {
      "sendgrid.api-key": "SG.your-api-key-here"
    }
  }'
```

Set `provider` to `""` or `null` to disable transactional routing and revert to SMTP.

### `DELETE /config`

Removes all `_providerConfig.ext-email-template.*` attributes from the realm.

```bash
curl -X DELETE "$KC/realms/myrealm/ext-email-template/config" \
  -H "Authorization: Bearer $TOKEN"
```

### `GET /templates`

Lists all supported email types and their available template variables.

```bash
curl "$KC/realms/myrealm/ext-email-template/templates" \
  -H "Authorization: Bearer $TOKEN"
```
