"use client";

import type { Team } from "@/lib/api";
import type { Scope } from "@/store/ui";

/**
 * Teams nest, so the sidebar renders them as a tree. Depth is capped at two
 * indents: deeper than that the labels lose more to indentation than they gain
 * in clarity, and the API can always be asked for descendants.
 */
function order(teams: Team[]): { team: Team; depth: number }[] {
  const byParent = new Map<string | undefined, Team[]>();
  for (const team of teams) {
    const key = team.parentTeamId && teams.some((t) => t.id === team.parentTeamId)
      ? team.parentTeamId
      : undefined;
    byParent.set(key, [...(byParent.get(key) ?? []), team]);
  }

  const out: { team: Team; depth: number }[] = [];
  const walk = (parentId: string | undefined, depth: number) => {
    for (const team of (byParent.get(parentId) ?? []).sort((a, b) => a.name.localeCompare(b.name))) {
      out.push({ team, depth: Math.min(depth, 2) });
      walk(team.id, depth + 1);
    }
  };
  walk(undefined, 0);
  return out;
}

export function Sidebar({
  teams,
  scope,
  onSelectScope,
  syncSummary,
}: {
  teams: Team[];
  scope: Scope;
  onSelectScope: (scope: Scope) => void;
  syncSummary: string;
}) {
  return (
    <aside className="sidebar">
      <div className="brand">
        <strong>Kanso</strong>
        <span>簡素</span>
      </div>

      <div>
        <div className="nav-label">Views</div>
        <button
          className="nav-item"
          aria-current={scope.kind === "all"}
          onClick={() => onSelectScope({ kind: "all" })}
        >
          <span>All tickets</span>
        </button>
      </div>

      <div>
        <div className="nav-label">Teams</div>
        {teams.length === 0 && (
          <div style={{ padding: "4px 6px", color: "var(--text-faint)", fontSize: 12 }}>
            No team yet
          </div>
        )}
        {order(teams).map(({ team, depth }) => (
          <button
            key={team.id}
            className={`nav-item nav-depth-${depth}`}
            aria-current={scope.kind === "team" && scope.id === team.id}
            onClick={() => onSelectScope({ kind: "team", id: team.id })}
            title={`${team.name} — prefix ${team.key}`}
          >
            <span>{team.name}</span>
            <span className="count">{team.key}</span>
          </button>
        ))}
      </div>

      <div style={{ marginTop: "auto", padding: "0 6px", color: "var(--text-faint)", fontSize: 11 }}>
        {syncSummary}
      </div>
    </aside>
  );
}
