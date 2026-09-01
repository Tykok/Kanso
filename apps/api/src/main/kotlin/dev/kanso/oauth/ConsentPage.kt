package dev.kanso.oauth

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
 * A pure function of five values, so what it says and what it refuses to say can be
 * tested without a servlet. The refusal is the important half: a client registers itself
 * unauthenticated and picks its own `client_name`, so that name is a stranger's text
 * printed in Kanso's voice next to an Authorise button. Every interpolation goes through
 * [esc], with no exceptions for values that "cannot" contain markup — the argument for
 * an exception is exactly the argument that gets one wrong later.
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
	 */
	fun render(
		clientName: String,
		email: String,
		scopes: List<String>,
		clientId: String,
		state: String,
	): String {
		val name = esc(clientName)
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
	<p class="asks">It is asking to:</p>
	<ul class="scopes">
$scopeLines
	</ul>
	<p class="note">
		It acts as you, and reaches exactly what you reach — the teams you belong to, and
		nothing else. You can withdraw this at any time in Settings, under Connected
		applications.
	</p>
	<div class="actions">
		<form method="post" action="/oauth2/authorize">
$hidden
$scopeInputs
			<button class="primary" type="submit">Authorise</button>
		</form>
		<form method="post" action="/oauth2/authorize">
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

.scopes {
	margin: 0 0 18px;
	padding: 12px 14px 12px 30px;
	list-style: disc;
	background: var(--accent-soft);
	border-radius: var(--radius);
}
.scopes li + li { margin-top: 6px; }

.note {
	margin: 0 0 22px;
	padding-top: 14px;
	border-top: 1px solid var(--rule);
	font-size: 12px;
	color: var(--muted-foreground);
}

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
