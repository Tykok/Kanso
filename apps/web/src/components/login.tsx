"use client";

import { API_URL, type AuthMode } from "@/lib/api";

/**
 * Only providers the server actually has credentials for are offered — a button
 * that leads to a broken redirect is worse than no button.
 */
export function LoginScreen({ mode }: { mode?: AuthMode }) {
  return (
    <div className="centered">
      <div className="brand" style={{ justifyContent: "center" }}>
        <strong style={{ fontSize: 20 }}>Kanso</strong>
        <span>簡素</span>
      </div>
      <p style={{ color: "var(--text-dim)", maxWidth: 360 }}>
        A keyboard-first tracker. Postgres holds the truth; Notion keeps a readable copy.
      </p>

      {mode?.providers.length ? (
        <div style={{ display: "flex", gap: 8 }}>
          {mode.providers.map((provider) => (
            <a
              key={provider.id}
              className="button button-primary"
              href={`${API_URL}${provider.authorizeUrl}`}
            >
              Continue with {provider.label}
            </a>
          ))}
        </div>
      ) : (
        <p className="error" style={{ maxWidth: 420 }}>
          No sign-in provider is configured on the API. Set <code>GOOGLE_CLIENT_ID</code> and{" "}
          <code>GOOGLE_CLIENT_SECRET</code> (or the GitHub pair), or run the API with{" "}
          <code>KANSO_AUTH_MODE=dev</code> for local work.
        </p>
      )}
    </div>
  );
}
