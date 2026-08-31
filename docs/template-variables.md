# Template Variables

When an email is routed to a transactional provider, the dynamic template data is populated with variables specific to that email type plus a set of base variables present on every send.

---

## Base variables

Every template receives these variables regardless of email type.

| Variable        | Description                                                           |
| --------------- | --------------------------------------------------------------------- |
| `realmName`     | Realm display name, or capitalised realm ID if no display name is set |
| `userEmail`     | Recipient email address                                               |
| `userFirstName` | User first name                                                       |
| `userLastName`  | User last name                                                        |
| `username`      | Keycloak username                                                     |

---

## Per-type variables

### `password-reset`

Sent from the forgot-password flow.

| Variable                  | Type   | Description                                                 |
| ------------------------- | ------ | ----------------------------------------------------------- |
| `link`                    | string | Action link the user must click to reset their password     |
| `linkExpiration`          | number | Link validity in minutes                                    |
| `linkExpirationFormatted` | string | Human-readable expiration (e.g. `"1 hour"`, `"30 minutes"`) |

---

### `email-verification`

Sent during registration or when an admin requests email verification.

| Variable                  | Type   | Description               |
| ------------------------- | ------ | ------------------------- |
| `link`                    | string | Verification link         |
| `linkExpiration`          | number | Link validity in minutes  |
| `linkExpirationFormatted` | string | Human-readable expiration |

---

### `executeActions`

Sent by admin when required actions (update password, verify email, etc.) are triggered.

| Variable                  | Type   | Description                                           |
| ------------------------- | ------ | ----------------------------------------------------- |
| `link`                    | string | Action link                                           |
| `linkExpiration`          | number | Link validity in minutes                              |
| `linkExpirationFormatted` | string | Human-readable expiration                             |
| `requiredActionsText`     | string | Comma-separated list of required action display names |

`requiredActionsText` is localized in the email's
[effective locale](configuration.md#one-locale-per-email) - the language of the template that was
actually selected, so the action names always match the body they're injected into. Display names come from
Keycloak's own `login` theme message bundle (e.g. `updatePasswordTitle`, `emailVerifyTitle`), so
they're translated into every language Keycloak itself ships (plus any realm-level localization
overrides), not a separate hardcoded list. A handful of rare/custom required actions have no
matching bundle key and fall back to a humanized version of their raw ID (e.g. `SOME_CUSTOM_ACTION`
→ "Some Custom Action") - English-only, since there's no general-purpose translated name for those.

---

### `email-update-confirmation`

Sent to the **new** email address when a user requests an email change.

| Variable                  | Type   | Description                           |
| ------------------------- | ------ | ------------------------------------- |
| `link`                    | string | Confirmation link                     |
| `linkExpiration`          | number | Link validity in minutes              |
| `linkExpirationFormatted` | string | Human-readable expiration             |
| `newEmail`                | string | The new email address being confirmed |

> Note: this email is sent to the new (unconfirmed) address, not the current one.

---

### `email-verification-with-code`

OTP-style verification using a short code instead of a link.

| Variable | Type   | Description               |
| -------- | ------ | ------------------------- |
| `code`   | string | Numeric verification code |

---

### `identity-provider-link`

Sent to confirm linking an existing Keycloak account with an external identity provider.

| Variable                      | Type   | Description                             |
| ----------------------------- | ------ | --------------------------------------- |
| `link`                        | string | Confirmation link                       |
| `linkExpiration`              | number | Link validity in minutes                |
| `linkExpirationFormatted`     | string | Human-readable expiration               |
| `identityProviderDisplayName` | string | Display name of the identity provider   |
| `identityProviderAlias`       | string | Internal alias of the identity provider |

---

### `org-invite`

Invitation to join an organization (requires the keycloak-orgs extension).

| Variable                  | Type   | Description                             |
| ------------------------- | ------ | --------------------------------------- |
| `link`                    | string | Invitation acceptance link              |
| `linkExpiration`          | number | Link validity in minutes                |
| `linkExpirationFormatted` | string | Human-readable expiration               |
| `organizationName`        | string | Name of the organization                |
| `firstName`               | string | Invitee first name (if set on the user) |
| `lastName`                | string | Invitee last name (if set on the user)  |

---

## Event notification emails

These are sent when Keycloak's email notification for login events is enabled. All event templates share the same base variables plus any event-specific ones listed below.

### Common event variables

| Variable            | Type   | Description                                                              |
| ------------------- | ------ | ------------------------------------------------------------------------- |
| `eventDate`         | number | Unix timestamp (milliseconds) of the event                                |
| `eventDateFormatted` | string | Human-readable date/time, rendered in the email's [effective locale](configuration.md#one-locale-per-email) and the server's local timezone |
| `eventIpAddress`    | string | IP address associated with the event                                     |

### `event-login_error`

Failed login attempt notification. No additional variables beyond the common event set.

### `event-update_password`

Password changed notification. No additional variables.

### `event-remove_totp`

TOTP device removed notification. No additional variables.

### `event-update_totp`

TOTP device added or updated notification. No additional variables.

### `event-remove_credential`

Credential removed notification.

| Variable         | Type   | Description                         |
| ---------------- | ------ | ----------------------------------- |
| `credentialType` | string | Type of credential that was removed |

### `event-update_credential`

Credential updated notification.

| Variable         | Type   | Description                         |
| ---------------- | ------ | ----------------------------------- |
| `credentialType` | string | Type of credential that was updated |

### `event-user_disabled_by_temporary_lockout`

Temporary account lockout notification. No additional variables.

### `event-user_disabled_by_permanent_lockout`

Permanent account lockout notification. No additional variables.

---

## Third-party extension emails

Emails from other extensions (e.g. [keycloak-magic-link](https://github.com/p2-inc/keycloak-magic-link), [keycloak-orgs](https://github.com/p2-inc/keycloak-orgs)) are intercepted through the generic `send()` path. The template name is the FTL filename without the `.ftl` suffix:

| Extension           | Template name      | Notes                                                                                        |
| ------------------- | ------------------ | -------------------------------------------------------------------------------------------- |
| keycloak-magic-link | `magic-link-email` | String/numeric attributes from the extension are forwarded; complex objects are filtered out |
| keycloak-orgs       | `invitation-email` | Same forwarding rules apply                                                                  |

Configure these the same way as built-in types — add a `_providerConfig.ext-email-template.template.<name>` realm attribute with the appropriate template ID.
