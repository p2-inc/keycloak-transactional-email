# keycloak-transactional-email

[![Maven Central](https://img.shields.io/maven-central/v/io.phasetwo.keycloak/keycloak-transactional-email)](https://central.sonatype.com/artifact/io.phasetwo.keycloak/keycloak-transactional-email) [![CI Build](https://github.com/p2-inc/keycloak-transactional-email/actions/workflows/ci.yml/badge.svg)](https://github.com/p2-inc/keycloak-transactional-email/actions/workflows/ci.yml)

A Keycloak extension that routes email sends to external transactional email providers — SendGrid, Brevo, Mailtrap, and others — using those providers' native **dynamic template** systems.

Instead of rendering a FreeMarker template and delivering it over SMTP, this extension passes the raw variable data (links, expiration times, realm name, user details, etc.) directly to the provider's API.

Routing is **per-email-type and per-realm**. Any email type without a configured template ID falls back to Keycloak's standard FreeMarker + SMTP flow, so you can adopt incrementally.

---

## How It Works

```
Keycloak triggers an email (e.g. password reset)
  ↓
TransactionalEmailTemplateProvider intercepts the send
  ↓
Checks realm config: is a provider + template ID configured for this email type?
  ├─ Yes → calls the provider's API with raw template variables
  └─ No  → falls back to FreeMarker + SMTP (standard Keycloak behaviour)
```

The extension is built around a generic `TransactionalEmailProvider` SPI, so new provider implementations can be added as separate modules without modifying core extension code.

---

## Installation

### Build from source

```bash
git clone https://github.com/p2-inc/keycloak-transactional-email
cd keycloak-transactional-email
mvn package -DskipTests
```

### Deploy to Keycloak

```bash
cp target/keycloak-transactional-email-*.jar /opt/keycloak/providers/
/opt/keycloak/bin/kc.sh build
/opt/keycloak/bin/kc.sh start
```

---

## Configuration

All configuration is stored as realm attributes, managed either via this extension's REST API or from the Admin Console when you run the Phase Two Keycloak image (whose `phasetwo-ui` admin theme has a realm-attributes page) — see **[docs/configuration.md](docs/configuration.md)** for the full reference.

Quick example via the REST API:

```bash
curl -X PUT "$KC/realms/myrealm/ext-email-template/config" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "provider": "sendgrid",
    "templates": {
      "password-reset": "d-abc123abc123",
      "email-verification": "d-def456def456"
    },
    "providerConfig": {
      "sendgrid.api-key": "SG.your-api-key-here"
    }
  }'
```

Set `provider` to `""` or `null` to disable transactional routing and revert to SMTP.

Each email type also supports locale-specific template overrides (e.g. `password-reset.nl`), resolved from the recipient's own stored locale (a region-qualified one like `nl-NL` falling back to its language first), then the realm's default locale, then the locale-less mapping. Whichever tier wins also fixes the locale the rest of that email is rendered in - sender name, formatted dates, action names - so a body and the variables this extension resolves for it can't end up in different languages. See [docs/configuration.md#locale-specific-templates](docs/configuration.md#locale-specific-templates).

The sender display name can likewise be overridden globally per locale, per email type, or both at once, since Keycloak's own realm-wide SMTP "From display name" setting has no notion of either - see [docs/configuration.md#locale-specific-sender-identity](docs/configuration.md#locale-specific-sender-identity).

---

## Template Variables

See **[docs/template-variables.md](docs/template-variables.md)** for the full list of variables available in each email type.

Every template receives a base set of variables:

| Variable        | Description             |
| --------------- | ----------------------- |
| `realmName`     | Realm display name      |
| `userEmail`     | Recipient email address |
| `userFirstName` | User first name         |
| `userLastName`  | User last name          |
| `username`      | Keycloak username       |

---

## Supported Email Types

| Template name                              | Trigger                          |
| ------------------------------------------ | -------------------------------- |
| `password-reset`                           | Forgot-password flow             |
| `email-verification`                       | Registration / admin verify      |
| `executeActions`                           | Admin-triggered required actions |
| `email-update-confirmation`                | User changes their email address |
| `email-verification-with-code`             | OTP-style verification           |
| `identity-provider-link`                   | Account linking to IdP           |
| `org-invite`                               | Organization invitation          |
| `event-login_error`                        | Failed login notification        |
| `event-update_password`                    | Password changed notification    |
| `event-remove_totp`                        | TOTP device removed              |
| `event-update_totp`                        | TOTP device updated              |
| `event-remove_credential`                  | Credential removed               |
| `event-update_credential`                  | Credential updated               |
| `event-user_disabled_by_temporary_lockout` | Temporary lockout                |
| `event-user_disabled_by_permanent_lockout` | Permanent lockout                |

Email types not listed here (e.g. `email-test` / SMTP test) are not routed and always use FreeMarker.

Third-party extension emails (e.g. `magic-link-email`, `invitation-email`) are also intercepted via the generic send path — see [docs/template-variables.md](docs/template-variables.md#third-party-extension-emails).

---

## Providers

| Provider                                                                           | ID           | Config keys                                                                           |
| ---------------------------------------------------------------------------------- | ------------ | ------------------------------------------------------------------------------------- |
| [SendGrid](https://sendgrid.com/en-us/solutions/email-api/dynamic-email-templates) | `sendgrid`   | `sendgrid.api-key`, `sendgrid.api-url`                                                |
| [Brevo](https://www.brevo.com/products/transactional-email/)                       | `brevo`      | `brevo.api-key`, `brevo.api-url`                                                      |
| [Postmark](https://postmarkapp.com/transactional-email)                            | `postmark`   | `postmark.server-token`, `postmark.api-url`                                           |
| [Mailtrap](https://mailtrap.io/email-sending/)                                     | `mailtrap`   | `mailtrap.api-token`, `mailtrap.api-url`                                              |
| [Mailgun](https://www.mailgun.com/products/send/transactional-email/)              | `mailgun`    | `mailgun.api-key`, `mailgun.domain`, `mailgun.api-url`                                |
| [Customer.io](https://customer.io/transactional-api/)                              | `customerio` | `customerio.api-key`, `customerio.api-url`                                            |
| [Resend](https://resend.com)                                                       | `resend`     | `resend.api-key`, `resend.api-url`                                                    |
| [AWS SES](https://aws.amazon.com/ses/)                                             | `awsses`     | `awsses.access-key-id`, `awsses.secret-access-key`, `awsses.region`, `awsses.api-url` |

> **Resend note:** no server-side template rendering — `templateData` must supply pre-rendered `html` and `subject` keys.
>
> **AWS SES note:** requests are signed with AWS Signature V4.

---

## Adding a New Provider

1. Implement `TransactionalEmailProvider`:

```java
public class AcmeEmailProvider implements TransactionalEmailProvider {
    @Override
    public void send(String templateId, Map<String, Object> templateData,
                     String toEmail, String toName, String fromEmail, String fromName)
            throws Exception {
        // call provider API
    }
    @Override public void close() {}
}
```

2. Implement and register the factory:

```java
@AutoService(TransactionalEmailProviderFactory.class)
public class AcmeEmailProviderFactory implements TransactionalEmailProviderFactory {
    @Override public String getId() { return "acme"; }
    @Override public TransactionalEmailProvider create(KeycloakSession session) {
        return new AcmeEmailProvider(session);
    }
    // ... init/postInit/close stubs
}
```

3. Configure via the API with `"provider": "acme"` and the appropriate `providerConfig` entries.

No changes to this extension's core code are needed.

---

## Local Development

See **[docs/development.md](docs/development.md)** for the Docker Compose setup, Makefile targets, end-to-end walkthrough, and test instructions.

---

## Build & Release

### CI

Every push to `main` and every pull request runs [`.github/workflows/ci.yml`](.github/workflows/ci.yml): builds with Maven, runs unit tests, and publishes a JaCoCo coverage report as a workflow artifact and Action summary.

### Auto-release

Every push to `main` also runs [`.github/workflows/release.yml`](.github/workflows/release.yml), which uses [qcastel/github-actions-maven-release](https://github.com/qcastel/github-actions-maven-release) to:

1. Strip `-SNAPSHOT` from the pom version (e.g. `0.1-SNAPSHOT` → `0.1`)
2. Create a signed git tag (e.g. `v0.1`) — the `v` prefix comes from `io.phasetwo.keycloak:oss-parent`
3. Push the tag back to the repo as `phasetwo-bot`
4. Bump the pom to the next `-SNAPSHOT` minor version (`0.2-SNAPSHOT`) and commit with `[ci skip]` so this workflow doesn't recurse

Released artifacts are published to [Maven Central](https://repo1.maven.org/maven2/io/phasetwo/keycloak/keycloak-transactional-email/).

## Compatibility

| Keycloak | Extension version |
| -------- | ----------------- |
| 26.5.x   | 0.1.x             |

---

## License

[Elastic License v2](https://www.elastic.co/licensing/elastic-license)

Built by [Phase Two](https://phasetwo.io).
