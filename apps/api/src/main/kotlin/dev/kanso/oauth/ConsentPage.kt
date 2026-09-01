package dev.kanso.oauth

import java.net.URI
import java.net.URISyntaxException
import java.time.Duration
import java.time.Instant

/**
 * The two things on this screen the client did not choose.
 *
 * Everything else the page shows comes out of a registration anybody may create: the name
 * is the client's own text, and `allowedRedirectHosts` defaults to Anthropic's own hosts,
 * so a stranger can register "Claude Code" with a `https://claude.ai/<anything>` callback
 * and send a member a link. A screen that displays only the attacker's field is a screen
 * that cannot be read carefully, however careful the escaping is — so these two travel
 * with it: where a code would actually go, and how old the registration is.
 *
 * @param redirectHosts the **hosts** of the registered redirect URIs, never the URIs. A
 *   path is more of the client's own text, and it is the host that decides who receives
 *   the code.
 * @param registeredAge how long ago the row was written, or null when the column holds
 *   nothing. A duration rather than an instant, so the page renders no clock of its own
 *   and the sentence is testable without one.
 */
data class ClientEvidence(val redirectHosts: List<String>, val registeredAge: Duration?) {

	companion object {

		fun of(redirectUris: Collection<String>, registeredAt: Instant?, now: Instant): ClientEvidence =
			ClientEvidence(
				// Parsed, and only the host kept. `RedirectUriPolicy` already refused
				// anything whose host is not loopback or a configured client host, so this
				// value is one of a short list — which is exactly what makes an unfamiliar
				// one worth reading.
				redirectHosts = redirectUris.mapNotNull(::hostOf).distinct().sorted(),
				registeredAge = registeredAt?.let { Duration.between(it, now) },
			)

		private fun hostOf(raw: String): String? = try {
			// `URI(String)` throws the checked exception; `URI.create` throws the
			// unchecked one. `RedirectUriPolicy` records the same trap.
			URI(raw.trim()).host?.lowercase()
		} catch (_: URISyntaxException) {
			null
		}
	}
}

/**
 * The question, as a document — served by the API, not by `apps/web`.
 *
 * The web app runs on another origin and the session cookie is `SameSite=Lax`, which
 * sends nothing at all on a cross-site POST: a decision posted from :3000 would reach
 * `/oauth2/authorize` with no session, and the library would have nobody to record a
 * consent for. The browser is already on the API's origin when the authorisation
 * endpoint redirects it here, so serving the page here makes the decision a first-party
 * form post that the library handles unaided. That is why this one screen owns its
 * stylesheet instead of using the design system, and why moving it back to `apps/web`
 * would break the flow rather than tidy it.
 *
 * A pure function of its arguments, so what it says and what it refuses to say can be
 * tested without a servlet. The refusal is the important half: a client registers itself
 * unauthenticated and picks its own `client_name`, so that name is a stranger's text
 * printed in Kanso's voice next to an Authorise button. Every interpolation goes through
 * [esc], with no exceptions for values that "cannot" contain markup — the argument for
 * an exception is exactly the argument that gets one wrong later.
 *
 * What the escaping cannot fix is a screen with nothing on it to weigh, which is why
 * [ClientEvidence] is an argument and not an option.
 *
 * Two forms rather than a form and a script. The library reads the decision off the
 * *presence* of `scope` — its own default page declines by resetting the form and
 * submitting it empty — and a post carrying none is answered with `access_denied` sent
 * to the client's own redirect URI. So Decline hands the member back to whatever asked
 * instead of stranding them on a page with nothing but a back button, and neither
 * button needs a line of JavaScript to mean what it says.
 */
object ConsentPage {

	/**
	 * @param scopes the scope identifiers the client asked for, in the order it asked.
	 *   Each is read out through [OAuthScopes.prose], which throws on one this instance
	 *   does not grant — the caller checks first, so that throw is a bug rather than a
	 *   half-rendered page.
	 * @param state the library's consent nonce. Opaque here, and posted back untouched.
	 * @param evidence the two facts the client did not pick — see [ClientEvidence]. Not
	 *   defaulted, because a caller that forgot it would render a screen that looks
	 *   finished and says nothing checkable.
	 * @param authorizeAction where both buttons post. **The context path belongs in it.**
	 *   The library resolves the redirect *to* this page with the context path
	 *   (`resolveConsentUri` calls `setContextPath`), and `AuthorizationServerSettings`'
	 *   `/oauth2/authorize` is context-path relative — so under
	 *   `server.servlet.context-path=/kanso` the browser is standing on
	 *   `/kanso/oauth/consent` and a root-absolute `/oauth2/authorize` 404s. Both buttons.
	 *   `ConsentController` builds it, and `McpBearerFilter` and
	 *   `ConsentController.returnUrl` record the two other instances of the same bug on
	 *   this branch. A parameter rather than a constant so this stays a pure function of a
	 *   deployment it cannot see.
	 */
	fun render(
		clientName: String,
		email: String,
		scopes: List<String>,
		clientId: String,
		state: String,
		evidence: ClientEvidence,
		authorizeAction: String,
	): String {
		val name = esc(clientName)
		val hosts = evidence.redirectHosts
			.takeIf { it.isNotEmpty() }
			?.joinToString(", ") { esc(it) }
			?: "an address this server cannot read"
		val registered = esc(age(evidence.registeredAge))
		// Escaped like everything else. It is built from the deployment's own context path
		// rather than from a request parameter, so there is nothing here a stranger wrote —
		// and the argument for an exception is exactly the argument that gets one wrong
		// later, which this file already says once.
		val action = esc(authorizeAction)
		val hidden = listOf(
			"""<input type="hidden" name="client_id" value="${esc(clientId)}">""",
			"""<input type="hidden" name="state" value="${esc(state)}">""",
		).joinToString("\n\t\t\t", prefix = "\t\t\t")
		val scopeInputs = scopes.joinToString("\n\t\t\t", prefix = "\t\t\t") {
			"""<input type="hidden" name="scope" value="${esc(it)}">"""
		}
		val scopeLines = scopes.joinToString("\n\t\t", prefix = "\t\t") {
			"<li>${esc(OAuthScopes.prose(it))}</li>"
		}

		return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Authorise $name — Kanso</title>
<style>
$STYLESHEET
</style>
</head>
<body>
<main class="card">
	<p class="mark">Kanso</p>
	<h1>Let $name act as you?</h1>
	<p class="lede">Signed in as <span class="email">${esc(email)}</span>.</p>
	<dl class="evidence">
		<dt>Sends your code to</dt>
		<dd>$hosts</dd>
		<dt>Registered with Kanso</dt>
		<dd>$registered</dd>
	</dl>
	<p class="asks">It is asking to:</p>
	<ul class="scopes">
$scopeLines
	</ul>
	<p class="note">
		It acts as you, and reaches exactly what you reach — the teams you belong to, and
		nothing else. You can withdraw this at any time in Settings, under Connected
		applications.
	</p>
	<p class="note weigh">
		Any application may register itself here and choose its own name, so the name above
		is its word rather than ours. The two lines that are not — the address and the age —
		are worth a second look: a registration minutes old that you did not just create, or a
		host you do not recognise, is one to decline.
	</p>
	<div class="actions">
		<form method="post" action="$action">
$hidden
$scopeInputs
			<button class="primary" type="submit">Authorise</button>
		</form>
		<form method="post" action="$action">
$hidden
			<button class="ghost" type="submit">Decline</button>
		</form>
	</div>
</main>
</body>
</html>
"""
	}

	/**
	 * How long ago, in the coarsest unit that is still true.
	 *
	 * Coarse on purpose: the member is being asked to recognise something they did, and
	 * "4 minutes ago" answers that where a timestamp in their least favourite timezone
	 * does not. Rounded down by integer division, so it never overstates the age of a
	 * registration — the young end is the dangerous end.
	 */
	private fun age(age: Duration?): String = when {
		age == null -> "at a time this server did not record"
		// A clock that moved backwards, or a row written in the same second. Either way
		// the honest reading is "just now", and it is the reading that invites suspicion.
		age.isNegative || age.toMinutes() < 1 -> "less than a minute ago"
		age.toHours() < 1 -> ago(age.toMinutes(), "minute")
		age.toDays() < 1 -> ago(age.toHours(), "hour")
		else -> ago(age.toDays(), "day")
	}

	private fun ago(count: Long, unit: String): String = "$count $unit${if (count == 1L) "" else "s"} ago"

	/**
	 * The five XML entities, `&` first so the others' output is not re-escaped.
	 *
	 * Apostrophe as `&#39;` rather than `&apos;`: the named form is XML, not HTML 4, and
	 * this page is served as `text/html`.
	 */
	private fun esc(value: String): String = value
		.replace("&", "&amp;")
		.replace("<", "&lt;")
		.replace(">", "&gt;")
		.replace("\"", "&quot;")
		.replace("'", "&#39;")

	/**
	 * Kanso's tokens as literals, because there is nothing here to read them from.
	 *
	 * Copied from `apps/web/src/styles/tokens.css` — its `:root` block and its `.dark`
	 * one, plus the default `indigo` accent. Copied rather than imported, and that is
	 * the cost of the ruling above: this page is served by the API and can load nothing
	 * from :3000, so a token changed there has to be changed here too. Kept to the dozen
	 * values this page actually draws, so the copy stays small enough to be re-read.
	 *
	 * The theme comes from `prefers-color-scheme`, not from the member's stored
	 * preference: that preference lives in the app's own storage on another origin, and
	 * is unreadable from here. The system's answer is the closest honest guess.
	 */
	private val STYLESHEET = """
:root {
	--background: oklch(0.988 0.002 262);
	--foreground: oklch(0.22 0.012 262);
	--muted-foreground: oklch(0.52 0.011 262);
	--card: #ffffff;
	--border: oklch(0.925 0.005 262);
	--rule: oklch(0.86 0.006 262);
	--primary: oklch(0.52 0.14 262);
	--primary-foreground: #ffffff;
	--accent-soft: oklch(0.965 0.021 262);
	--elev-panel: 0 1px 2px rgb(20 20 30 / 8%), 0 18px 44px rgb(20 20 30 / 10%);
	--radius: 6px;
	--panel-radius: 10px;
	color-scheme: light;
}

@media (prefers-color-scheme: dark) {
	:root {
		--background: oklch(0.185 0.008 262);
		--foreground: oklch(0.935 0.006 262);
		--muted-foreground: oklch(0.715 0.011 262);
		--card: oklch(0.228 0.010 262);
		--border: oklch(0.315 0.012 262);
		--rule: oklch(0.315 0.012 262);
		--primary: oklch(0.70 0.13 262);
		--primary-foreground: oklch(0.185 0.008 262);
		--accent-soft: oklch(0.30 0.048 262);
		--elev-panel: 0 1px 2px rgb(0 0 0 / 30%), 0 18px 44px rgb(10 10 16 / 30%);
		color-scheme: dark;
	}
}

* { box-sizing: border-box; }

body {
	margin: 0;
	min-height: 100vh;
	display: grid;
	place-items: center;
	padding: 24px;
	background: var(--background);
	color: var(--foreground);
	font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
	font-size: 13px;
	line-height: 1.55;
}

.card {
	width: 100%;
	max-width: 420px;
	padding: 26px 26px 22px;
	background: var(--card);
	border: 1px solid var(--border);
	border-radius: var(--panel-radius);
	box-shadow: var(--elev-panel);
}

.mark {
	margin: 0 0 18px;
	font-size: 11px;
	letter-spacing: 0.14em;
	text-transform: uppercase;
	color: var(--muted-foreground);
}

h1 {
	margin: 0 0 6px;
	font-size: 18px;
	font-weight: 600;
	line-height: 1.3;
	/* A self-chosen name can be one 300-character word. */
	overflow-wrap: anywhere;
}

.lede { margin: 0 0 18px; color: var(--muted-foreground); }
.email { color: var(--foreground); font-weight: 500; }
.asks { margin: 0 0 8px; }

.evidence {
	margin: 0 0 18px;
	padding: 10px 14px;
	display: grid;
	grid-template-columns: auto 1fr;
	gap: 4px 14px;
	font-size: 12px;
	border: 1px solid var(--border);
	border-radius: var(--radius);
}
.evidence dt { color: var(--muted-foreground); }
/* A host is short; the fallback sentence is not, and neither may widen the card. */
.evidence dd { margin: 0; overflow-wrap: anywhere; }

.scopes {
	margin: 0 0 18px;
	padding: 12px 14px 12px 30px;
	list-style: disc;
	background: var(--accent-soft);
	border-radius: var(--radius);
}
.scopes li + li { margin-top: 6px; }

.note {
	margin: 0 0 14px;
	padding-top: 14px;
	border-top: 1px solid var(--rule);
	font-size: 12px;
	color: var(--muted-foreground);
}

.weigh { margin-bottom: 22px; padding-top: 0; border-top: 0; }

.actions { display: flex; flex-direction: row-reverse; gap: 8px; }
.actions form { margin: 0; flex: 1; }

button {
	width: 100%;
	min-height: 36px;
	padding: 0 14px;
	font: inherit;
	font-weight: 500;
	border-radius: var(--radius);
	border: 1px solid transparent;
	cursor: pointer;
}
button:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }

.primary { background: var(--primary); color: var(--primary-foreground); }
.ghost { background: transparent; color: var(--muted-foreground); border-color: var(--border); }

@media (pointer: coarse) { button { min-height: 44px; } }
""".trimIndent()
}
