"use client";

import { ProviderButtons } from "@/components/setup/fields";
import { API_URL, type AuthMode } from "@/lib/api";
import { Seal } from "./ui/seal";

/**
 * The seal stands in for the full lockup here: at this size — and on the one
 * screen with nothing else on it — the mark alone stays sharp where the full
 * wordmark used to blur into a grey smear.
 *
 * Only providers the server actually has credentials for are offered — a button
 * that leads to a broken redirect is worse than no button.
 */
export function LoginScreen({ mode }: { mode?: AuthMode }) {
  return (
    <div className="flex min-h-screen flex-col justify-center gap-6 px-[72px]">
      <div className="flex items-center gap-3">
        <Seal size={26} />
        <div className="flex flex-col leading-tight">
          <span className="text-21 font-medium tracking-tight">Kanso</span>
          <span className="text-12 tracking-[0.16em] text-faint">簡素</span>
        </div>
      </div>

      <p className="m-0 max-w-[400px] text-15 leading-relaxed text-muted-foreground">
        A keyboard-first tracker. Postgres holds the truth; Notion keeps a readable copy.
      </p>

      {mode?.providers.length ? (
        <div className="max-w-[320px]">
          <ProviderButtons
            primary
            providers={mode.providers.map((provider) => ({
              id: provider.id,
              label: provider.label,
              href: `${API_URL}${provider.authorizeUrl}`,
            }))}
          />
          <p className="m-0 mt-2 text-11 text-faint">
            Only providers actually configured on the API are offered.
          </p>
        </div>
      ) : (
        <p className="m-0 max-w-[420px] text-12 text-urgent">
          No sign-in provider is configured on the API. Set <code>GOOGLE_CLIENT_ID</code> and{" "}
          <code>GOOGLE_CLIENT_SECRET</code> (or the GitHub pair), or run the API with{" "}
          <code>KANSO_AUTH_MODE=dev</code> for local work.
        </p>
      )}
    </div>
  );
}
