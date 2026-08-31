# Task 1 spike — Spring Authorization Server 7.1.0 on Boot 4.1.0

Throwaway code, kept findings. Everything below was observed on a running instance
(`bootRun`, `KANSO_AUTH_MODE=dev`, Postgres 16 on 5433) or read out of the jars on
disk — nothing here is inferred from documentation.

**Where later tasks disagree with this file, this file wins.**

Resolved versions: `spring-security-oauth2-authorization-server:7.1.0`,
`spring-security-config:7.1.1`, `spring-boot-security-oauth2-authorization-server:4.1.0`,
`jackson-databind:3.1.4` (Jackson **3** — `tools.jackson.*`), Spring Security core 7.1.0.

---

## 1. The entry point the plan expected does not exist any more

`OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(HttpSecurity)` is **gone**
in Spring Security 7.1. The class survives, but it moved and changed shape:

- **New FQN:** `org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration`
- **New home:** `spring-security-config`, not the authorization-server jar.
- **New shape:** a plain `@Configuration` exposing
  `authorizationServerSecurityFilterChain(HttpSecurity)` and a static
  `jwtDecoder(JWKSource<SecurityContext>)`, plus a `RegisterMissingBeanPostProcessor`
  that supplies a default `AuthorizationServerSettings` **only when that class is
  imported**. There is no static helper to call from our own `@Bean` method.

So the chain is built by hand, and it works:

```kotlin
val configurer = OAuth2AuthorizationServerConfigurer()   // spring-security-config
http
    .securityMatcher(configurer.endpointsMatcher)
    .with(configurer) { }
    .csrf { it.disable() }
    .authorizeHttpRequests { it.anyRequest().authenticated() }
```

`OAuth2AuthorizationServerConfigurer` is at
`org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer`
— also in `spring-security-config`, alongside one configurer per endpoint
(`OAuth2AuthorizationEndpointConfigurer`, `OAuth2TokenEndpointConfigurer`,
`OAuth2ClientRegistrationEndpointConfigurer`, `OidcConfigurer`, …).

**Consequence for Ruling P1.** The ruling folded Tasks 3/4/5 together because
`applyDefaultSecurity` "cannot start without the beans Task 5 supplies". The premise
is wrong — that method is gone — but *the conclusion still holds for a different
reason*: with the configurer used directly, **`AuthorizationServerSettings` has no
default at all** and the context fails to start without it. Keep the fold; correct
the reason.

## 2. Beans we must declare ourselves, and one Boot gives us for free

`OAuth2AuthorizationServerAutoConfiguration`
(`org.springframework.boot.security.oauth2.server.authorization.autoconfigure.servlet.*`,
module `spring-boot-security-oauth2-authorization-server:4.1.0`) has an inner
`OAuth2AuthorizationServerConfiguration` supplying `RegisteredClientRepository` and
`AuthorizationServerSettings` — but it is gated on `RegisteredClientsConfiguredCondition`,
i.e. on `spring.security.oauth2.authorizationserver.client.*` properties. Kanso
registers its client in code, so **that condition never fires and neither bean arrives.**
Both are ours to declare.

`OAuth2AuthorizationServerJwtAutoConfiguration` *does* fire, and supplies a `JWKSource`
whose **RSA key is generated in memory at every startup**. Harmless for us — access
tokens are `OAuth2TokenFormat.REFERENCE`, opaque, so nothing is verified by signature —
but `/oauth2/jwks` advertises a key that changes on every restart. If Kanso ever issues
a JWT (an `id_token`, say), the key has to be persisted first.

`OAuth2AuthorizationServerWebSecurityConfiguration` also backs off: Kanso already has a
`SecurityFilterChain`, so Boot contributes no chain of its own. Nothing to gate.

## 3. The exact names later tasks reference

Package `org.springframework.security.oauth2.server.authorization` (the SAS jar):

| Need | Exact name |
|---|---|
| authorisation record | `OAuth2Authorization` |
| store | `OAuth2AuthorizationService`, `InMemoryOAuth2AuthorizationService`, `JdbcOAuth2AuthorizationService(JdbcOperations, RegisteredClientRepository)` |
| consent store | `OAuth2AuthorizationConsentService`, `InMemoryOAuth2AuthorizationConsentService`, `JdbcOAuth2AuthorizationConsentService` |
| token kinds | `OAuth2TokenType` |
| clients | `client.RegisteredClient`, `client.RegisteredClientRepository`, `client.InMemoryRegisteredClientRepository`, `client.JdbcRegisteredClientRepository` |
| settings | `settings.AuthorizationServerSettings`, `settings.ClientSettings`, `settings.TokenSettings`, `settings.OAuth2TokenFormat`, `settings.ConfigurationSettingNames` |
| token plumbing | `token.OAuth2TokenCustomizer`, `token.OAuth2TokenGenerator`, `token.OAuth2AccessTokenGenerator`, `token.DelegatingOAuth2TokenGenerator`, `token.JwtGenerator` |
| authorize-time hooks | `authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider`, `authentication.OAuth2AuthorizationCodeRequestAuthenticationToken`, `authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator`, `authentication.OAuth2AuthorizationConsentAuthenticationProvider` |

Reading an `OAuth2Authorization`: `getAttribute(String)`, `getAttributes()`,
`getPrincipalName()`, `getAuthorizedScopes()`, `getAccessToken()`, `getRefreshToken()`,
`getToken(Class)`, `getToken(String)`. **The `tokens` map itself is private and has no
accessor** — a plan step that iterates it will not compile.

`OAuth2TokenFormat.REFERENCE` exists and behaves: the spike's token came back as an
opaque 128-char string, not a JWT.

Nothing in the jar has `Resource` in its name. See §6.

There is also `web.DefaultConsentPage` — the library now ships a consent page of its
own. Task 9 writes ours anyway (Kanso styling, Kanso copy), but it is worth one look
before writing the form: whatever field names that page posts are the contract the
library's `OAuth2AuthorizationConsentAuthenticationConverter` expects.

## 4. Schema files — the three, and the Postgres edit

In-jar paths, for Task 2 to copy verbatim:

- `org/springframework/security/oauth2/server/authorization/client/oauth2-registered-client-schema.sql`
- `org/springframework/security/oauth2/server/authorization/oauth2-authorization-schema.sql`
- `org/springframework/security/oauth2/server/authorization/oauth2-authorization-consent-schema.sql`

The authorization schema's own header states the two required edits for Postgres, so
they are not us departing from the contract: every `blob` becomes `text`, every
`timestamp` becomes `timestamptz`. Applied that way, all three create cleanly on
Postgres 16 — verified.

`oauth2_authorization.principal_name` is `varchar(200)`. Relevant to §5.

## 5. The finding that would have cost the most: our principal cannot be read back

`OAuth2Authorization.attributes` holds two keys, and the library persists both as JSON:

```
java.security.Principal
org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
```

The first is the whole `Authentication` from the authorize request. With Kanso's
principals that is a `UsernamePasswordAuthenticationToken` wrapping a `KansoDevUser`
(or `KansoLocalUser`, or `KansoOidcUser`). **Writing works. Reading throws.**

```
java.lang.IllegalArgumentException: Could not resolve type id 'dev.kanso.auth.KansoDevUser'
as a subtype of `java.lang.Object`: Configured `PolymorphicTypeValidator`
(of type `tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator`) denied resolution
```

Observed end to end: with `JdbcOAuth2AuthorizationService`, `/oauth2/authorize` returns a
code and the row lands in `oauth2_authorization`, then `/oauth2/token` fails with an
empty body because the row cannot be deserialised. The failure is **invisible until the
store is Jdbc** — the in-memory service never serialises, so every earlier probe passed.

A second, quieter defect sits next to it. Every Kanso principal implements
`getName()` as the **display name** (`Principals.kt`), so `principal_name` was
`spike`, not `spike@kanso.local` and not a user id. A display name is neither unique
nor stable, so it cannot be the key `McpBearerFilter` maps a bearer token back through.

**One change fixes both, and it is validated.** Give the authorization-server chain a
filter that leaves only the Kanso user id in the `SecurityContext`:

```kotlin
UsernamePasswordAuthenticationToken(user.id.toString(), null, authorities)
```

Result, same flow, `JdbcOAuth2AuthorizationService`, nothing else changed:

- `principal_name` = `6df0c1a8-000b-4ed0-8314-0fb47d35a414` — the Kanso user id
- token issued, `token_type=Bearer`, `access_token_type` column written
- zero `Could not resolve type id`

Nothing Kanso-specific reaches the JSON, so no Jackson mixin, no
`PolymorphicTypeValidator` allowlist, and no serialisation contract to keep in step
with `Principals.kt`. `McpBearerFilter` then reads `principalName`, parses a `UUID`,
and loads the user — which is what Task 7 wanted anyway.

The alternative — registering the three principal classes with the row mapper's
`JsonMapper` — was not pursued: it makes a persisted format out of internal classes.

## 6. RFC 8707 (`resource`): carried, readable, unenforced

Three questions, three answers.

**Did the token request succeed with `resource` present?** Yes, at both endpoints, both
times. `GET /oauth2/authorize?…&resource=http://localhost:8080/api/mcp` returned a
normal 302 with a code; `POST /oauth2/token` with the same `resource` returned 200 and a
token. Not rejected, and not a special case — the authorize converter copies **every**
parameter it does not itself consume into `additionalParameters`. Its bytecode excludes
exactly `response_type`, `client_id`, `redirect_uri`, `scope`, `state`; the token
endpoint's excludes `grant_type`, `client_id`, `code`, `redirect_uri`.

**Is it recorded anywhere the server can read back?** Yes. Exact path:

```kotlin
authorization
    .getAttribute<OAuth2AuthorizationRequest>(OAuth2AuthorizationRequest::class.java.name)
    ?.additionalParameters
    ?.get("resource")          // "http://localhost:8080/api/mcp"
```

Confirmed both in memory and as persisted JSON in `oauth2_authorization.attributes`.
One `resource` arrives as a `String`; repeated ones as `Array<String>` — both branches
need handling. Only the **authorize-time** value is stored; the token-endpoint
`resource` is parsed and dropped, so the two cannot be compared after the fact.

**Verdict: surfaced but unenforced.** Task 9's audience check reads the attribute above
— no `OAuth2TokenCustomizer` is needed to put it there. But the library validates
nothing: it does not check the value against anything, does not reject an unknown
resource, and puts no `aud` on the token (opaque, so there are no claims at all). Every
audience-binding MUST in the spec is ours:

1. **Reject at authorize time** a `resource` that is not Kanso's MCP endpoint — a custom
   `OAuth2AuthorizationCodeRequestAuthenticationValidator`, which is the library's own
   extension point for exactly this.
2. **Enforce at `/api/mcp`** in `McpBearerFilter`: read the attribute, compare, refuse.

Both are load-bearing. Neither is optional.

## 7. Endpoint paths as actually served

`GET /.well-known/oauth-authorization-server`, from the running instance:

```json
{
  "issuer": "http://localhost:8080",
  "authorization_endpoint": "http://localhost:8080/oauth2/authorize",
  "token_endpoint": "http://localhost:8080/oauth2/token",
  "jwks_uri": "http://localhost:8080/oauth2/jwks",
  "revocation_endpoint": "http://localhost:8080/oauth2/revoke",
  "introspection_endpoint": "http://localhost:8080/oauth2/introspect",
  "response_types_supported": ["code"],
  "grant_types_supported": ["authorization_code", "client_credentials", "refresh_token",
                            "urn:ietf:params:oauth:grant-type:token-exchange"],
  "code_challenge_methods_supported": ["S256"],
  "tls_client_certificate_bound_access_tokens": true,
  "dpop_signing_alg_values_supported": ["RS256", "…"]
}
```

The spec's table is right about `/oauth2/authorize`, `/oauth2/token` and
`/oauth2/revoke`. Three things it does not say:

- **No `registration_endpoint`.** See §8 — this is the one that touches the goal.
- **No `scopes_supported`.** The metadata endpoint is configurable
  (`OAuth2AuthorizationServerMetadataEndpointConfigurer`) if we want to advertise
  `kanso:read kanso:write`. Scopes containing `:` gave no trouble anywhere.
- **No `authorization_response_iss_parameter_supported`,** and the 302's `Location`
  carried no `iss`. `AuthorizationServerSettings.Builder` has no switch for it and
  `ConfigurationSettingNames` has no such name: **SAS 7.1.0 does not implement
  RFC 9207.** The spec lists `iss` as a MUST, so it needs a hand-written
  `AuthenticationSuccessHandler` on the authorization endpoint that appends it to the
  redirect. Add it to Task 3, not Task 12.

Default access-token TTL is 300s (`expires_in: 299` observed). No refresh token was
issued, correctly: the spike's `RegisteredClient` declared only
`AuthorizationGrantType.AUTHORIZATION_CODE`. **The real client must also declare
`REFRESH_TOKEN`**, or refresh rotation — another of the spec's MUSTs — has nothing to
rotate.

## 8. Dynamic client registration does not do what the goal needs

`POST /connect/register` → **404**. The endpoint exists in the library
(`OAuth2ClientRegistrationEndpointConfigurer`, path settable via
`AuthorizationServerSettings.builder().clientRegistrationEndpoint(…)`) but is **off
until explicitly enabled**.

Enabling it is not enough, and this is the part that needs a decision.
`OAuth2ClientRegistrationAuthenticationProvider`'s bytecode requires the caller to
present an **initial access token bearing scope `client.create`**, which the provider
then invalidates — single-use, by design (RFC 7591 §3.1's protected-registration mode).
MCP clients register **unauthenticated**: nobody hands `claude mcp add` an initial
access token.

So the plan's goal — *"A member runs `claude mcp add --transport http kanso <url>/api/mcp`"* —
is not reachable through the library's DCR as shipped. Three ways out, in the order I
would rank them:

1. **Open registration, hand-written.** A small RFC 7591 endpoint that accepts an
   unauthenticated `POST`, validates `redirect_uris` against a narrow allowlist
   (loopback and the two Claude origins), and writes a `RegisteredClient`. Reaches the
   goal; adds an unauthenticated write endpoint, which is a real surface and wants
   rate limiting and a hard cap.
2. **Issue the initial access token from the Kanso UI.** The member clicks "connect an
   agent", gets a one-shot registration token, pastes it once. Keeps the library's
   protected DCR untouched; changes the goal's one-command story.
3. **No DCR: one pre-registered client per instance.** Simplest and smallest; needs
   confirmation that Claude Code and claude.ai accept a pre-configured client id when
   the metadata advertises no `registration_endpoint`. If they do, this is the least
   code by a wide margin.

**This is a spec question, not a task question.** Part one cannot be finished without
choosing, and the choice changes what Task 3 configures and whether a task is added.

`GET /.well-known/oauth-protected-resource` → 404, as expected; Task 6 writes it.

## 9. What survives this spike

`apps/api/build.gradle.kts` keeps one line —
`implementation("org.springframework.boot:spring-boot-starter-oauth2-authorization-server")`,
resolving to `7.1.0` via the Boot 4.1 BOM — and this file. The throwaway configuration
is deleted, and the three `oauth2_*` tables the spike hand-created in the local dev
database are dropped; Task 2's migration is what creates them for real.
