import type { Metadata } from "next";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { ContrastMatrix, DialogDemo, DropdownDemo } from "./sections";

export const metadata: Metadata = { title: "Design system — Kanso" };

/**
 * The tokens and every installed component on one screen.
 *
 * Not linked from the interface: it is a workbench, not a feature. It exists so a
 * change to --primary or --radius can be judged against every component at once
 * rather than discovered later on one screen that happened to use it.
 */

/** Paired so each swatch can show its foreground on its own background — the only
 *  check that matters for a colour token is whether text on it stays legible. */
const SURFACES = [
  { name: "background", fg: "text-foreground", bg: "bg-background" },
  { name: "card", fg: "text-card-foreground", bg: "bg-card" },
  { name: "popover", fg: "text-popover-foreground", bg: "bg-popover" },
  { name: "primary", fg: "text-primary-foreground", bg: "bg-primary" },
  { name: "secondary", fg: "text-secondary-foreground", bg: "bg-secondary" },
  { name: "muted", fg: "text-muted-foreground", bg: "bg-muted" },
  { name: "accent", fg: "text-accent-foreground", bg: "bg-accent" },
  { name: "destructive", fg: "text-destructive-foreground", bg: "bg-destructive" },
  { name: "success", fg: "text-success-foreground", bg: "bg-success" },
  { name: "warning", fg: "text-warning-foreground", bg: "bg-warning" },
];

const BUTTON_VARIANTS = ["default", "secondary", "destructive", "outline", "ghost", "link"] as const;
const BADGE_VARIANTS = ["default", "secondary", "destructive", "outline", "success", "warning"] as const;
const RADII = [
  { name: "rounded-sm", cls: "rounded-sm" },
  { name: "rounded-md", cls: "rounded-md" },
  { name: "rounded-lg", cls: "rounded-lg" },
  { name: "rounded-xl", cls: "rounded-xl" },
];

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="flex flex-col gap-4">
      <h2 className="text-sm font-semibold tracking-tight">{title}</h2>
      {children}
    </section>
  );
}

export default function DesignSystemPage() {
  return (
    <main className="mx-auto flex max-w-4xl flex-col gap-12 bg-background p-10 text-foreground">
      <header className="flex flex-col gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">Design system</h1>
        <p className="text-sm text-muted-foreground">
          Every token and component, in the current theme. Tune the values in{" "}
          <code className="rounded-sm bg-muted px-1.5 py-0.5">src/styles/tokens.css</code>.
        </p>
      </header>

      <Section title="Surfaces">
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          {SURFACES.map((surface) => (
            <div
              key={surface.name}
              className={`${surface.bg} ${surface.fg} rounded-lg border border-border p-4 text-xs`}
            >
              {surface.name}
            </div>
          ))}
        </div>
      </Section>

      <Section title="Contrast">
        <ContrastMatrix />
      </Section>

      <Section title="Radius">
        <div className="flex flex-wrap gap-3">
          {RADII.map((radius) => (
            <div key={radius.name} className="flex flex-col items-center gap-2">
              <div className={`size-16 border border-border bg-muted ${radius.cls}`} />
              <span className="text-xs text-muted-foreground">{radius.name}</span>
            </div>
          ))}
        </div>
      </Section>

      <Section title="Buttons">
        <div className="flex flex-wrap items-center gap-3">
          {BUTTON_VARIANTS.map((variant) => (
            <Button key={variant} variant={variant}>
              {variant}
            </Button>
          ))}
          <Button disabled>disabled</Button>
        </div>
      </Section>

      <Section title="Badges">
        <div className="flex flex-wrap items-center gap-3">
          {BADGE_VARIANTS.map((variant) => (
            <Badge key={variant} variant={variant}>
              {variant}
            </Badge>
          ))}
        </div>
      </Section>

      <Section title="Input">
        <div className="flex max-w-sm flex-col gap-3">
          <Input placeholder="Filter tickets…" />
          <Input placeholder="Disabled" disabled />
        </div>
      </Section>

      <Section title="Card">
        <Card className="max-w-sm">
          <CardHeader>
            <CardTitle>Notion mirror</CardTitle>
            <CardDescription>Pushed a moment ago.</CardDescription>
          </CardHeader>
          <CardContent className="text-sm text-muted-foreground">
            The mirror runs behind by design, so its state is shown per row.
          </CardContent>
          <CardFooter>
            <Badge variant="success">Notion</Badge>
          </CardFooter>
        </Card>
      </Section>

      <Section title="Overlays">
        <div className="flex flex-wrap items-center gap-3">
          <DialogDemo />
          <DropdownDemo />
        </div>
      </Section>
    </main>
  );
}
