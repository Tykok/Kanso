# Security

Kanso is a self-hosted application that carries an OAuth authorisation server, an
unauthenticated endpoint that writes rows, a webhook signer and a store of third-party
secrets encrypted at rest. Report a weakness in any of those **privately**.

## Reporting

**[Open a private advisory](https://github.com/Tykok/Kanso/security/advisories/new)** —
never a public issue, and never a pull request that fixes it and explains the hole in
its title. A public issue on a self-hosted product is a disclosure to every operator's
attacker and to no operator, since nobody runs an instance that updates itself.

Useful in a report: the version, how the instance is exposed (the published image
behind its own Caddy, or something else terminating TLS in front), and the smallest
request sequence that shows it. A proof of concept against your own instance is welcome;
one against somebody else's is not.

Kanso has one maintainer. Expect an acknowledgement in days rather than hours, and say
in the report if you are working to a disclosure deadline so it can be answered honestly
rather than silently missed.

## What is supported

The latest published image, `ghcr.io/tykok/kanso`. Fixes go out as a new tag; there are
no backports to older ones. Kanso is v1-in-progress and a single maintainer cannot
honestly promise a security branch per release.

## In scope

- The OAuth authorisation server: `/connect/register`, token issuance, the consent
  screen, revocation, and the rule that an MCP token is accepted at `/api/mcp` and
  refused everywhere else.
- Session authentication, and the WebSocket handshake that reuses the same cookie.
- Authorisation on the REST API: team scoping, saved views, drafts, and anything that
  lets one person read or move another team's work.
- Personal access tokens: their scopes, their storage, and what a revoked one can still
  do.
- Webhook delivery signatures — `X-Kanso-Signature`, `t=<unix>,v1=<hmac>` over the body.
- The Notion and Google secrets encrypted with AES-GCM under the key in `/data`, and
  anything that reads them back out in plaintext.
- The routing table in `docker/Caddyfile`, where a path reaching the wrong upstream is a
  security bug and not only a broken page.
- Rate limiting on unauthenticated writes, and anything that lets a caller choose the
  address it is limited on.

## What is a documented limitation rather than a vulnerability

These are all real, all deliberate, and all already written down. A report about one of
them will be answered with this section, so it is here to save you the write-up.

- **`KANSO_AUTH_MODE=dev` trusts an `X-Kanso-User` header and verifies nothing.** It
  exists so the Playwright suite can play two people in one test. It has to be asked for
  explicitly, it logs a loud warning, and it disables the agent door outright rather than
  issuing durable tokens behind an unverified identity. An instance deployed that way is
  a misconfiguration, and it is the one the software argues against out loud.
- **Publishing port 8080 yourself.** The JVM binds to `127.0.0.1` in the image and Caddy
  is the only thing exposed. Trusting a forwarded header at all is defensible *because*
  nothing but the proxy can reach the JVM; publishing 8080 removes that premise, and it
  is removed by the operator, not by Kanso.
- **`KANSO_TLS=off` with nothing in front.** That setting says an operator's proxy is the
  edge. With no proxy there, the client owns `X-Forwarded-For` and every address-based
  decision becomes the caller's to make.
- **A token has no scope narrower than a person.** `kanso:read` and `kanso:write` are the
  whole vocabulary, and a personal access token carries its owner's role — so an
  integration handed an owner's token can do what an owner can. The README says this
  above the `curl` example. Narrowing it is a feature request, and a welcome one.
- **Losing `/data`.** It holds the key that encrypts the stored Notion and Google secrets
  and Caddy's ACME state. Losing it makes those secrets unreadable and gets you
  rate-limited by Let's Encrypt inside a week. That is data loss the operator can prevent
  and Kanso cannot.
- **Notion sees what you consented to.** The mirror writes to databases in a workspace
  you connected, under a scope you picked on Notion's own consent screen. Somebody with
  access to that workspace reading mirrored tickets is the feature.
