import { expect, test, type Page } from "@playwright/test";
import {
  ADMIN,
  MEMBER,
  apiAs,
  openAs,
  seedInstance,
  seedMember,
  seedTeam,
  unique,
  uniqueKey,
  userIdOf,
} from "./support";

test.beforeAll(seedInstance);

/**
 * Scenario 26 — two people in one document. `KAN-25`.
 *
 * The only scenario in this suite that could not be written as a unit test at all, and
 * the reason is the whole ticket: presence and a block lock are *between* two browsers.
 * Every part of this is already proved in isolation — `doc-locks.test.ts` proves the
 * sentence, `block-lock-badge.test.tsx` proves it renders, `DocBlockLockTest` proves the
 * server refuses and expires — and none of that says the two halves ever meet. A lock
 * whose event never arrives, a roster that never repaints, a topic whose string differs
 * by one character between Kotlin and TypeScript: all three pass every test above this
 * one and fail on screen, silently, doing nothing.
 *
 * Both people are members of the seeded team, so every refusal asserted here is about the
 * **lock** and not about `TicketAccess`. A version of this test where the second person
 * simply could not write would pass with the entire feature deleted.
 *
 * The expiry assertions need a lock that lapses in seconds rather than in the default
 * thirty, so the stack this runs against is expected to set `KANSO_DOCS_LOCK_TTL=3s` —
 * `docker-compose.yml` passes it through and says why. Rather than assume, the test reads
 * the countdown the badge is drawing and **skips the two expiry claims** if the instance
 * is on a long TTL; the rest of the scenario holds either way. A test that silently waited
 * thirty seconds would be indistinguishable from a hung one.
 */

/** The block at `index`, by its own row rather than by a class the stylesheet owns. */
const blockAt = (page: Page, index: number) => page.getByTestId("doc-block").nth(index);

const editorIn = (page: Page, index: number) => blockAt(page, index).getByRole("textbox").first();

test("scenario 26 — two people on one page: presence, a lock, and what the second one is told", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  const teamName = unique("Together");
  const team = await seedTeam(api, { name: teamName, key: uniqueKey() });
  // The member has to be able to write here, or the lock refusal below would be a 403
  // wearing a 409's clothes.
  await seedMember(api, team.id, await userIdOf(MEMBER));

  // Seeded over HTTP rather than typed: the document is the stage, not the thing under
  // test, and scenario 18 already walks the template path with the mouse.
  const created = await api.post("/api/docs/pages", {
    data: { teamId: team.id, title: unique("Cycle notes"), templateSlug: "decision" },
  });
  expect(created.ok(), `Could not create the document: ${created.status()}`).toBeTruthy();
  const pageId = ((await created.json()) as { page: { id: string } }).page.id;

  const owner = await openAs(browser, ADMIN);
  await owner.goto(`/docs/${pageId}`);
  await expect(owner.getByTestId("doc-block").first()).toBeVisible();

  // --- nobody else is here yet ------------------------------------------------
  // Asserted before the second browser opens, so the appearance below is a *change* and
  // not a thing that was always on screen. An empty roster draws nothing at all.
  await expect(owner.getByTestId("doc-viewers")).toHaveCount(0);

  const member = await openAs(browser, MEMBER);
  await member.goto(`/docs/${pageId}`);
  await expect(member.getByTestId("doc-block").first()).toBeVisible();

  // --- presence, in both directions -------------------------------------------
  //
  // The owner learns about the member without reloading: nobody announced anything, the
  // member merely subscribed to the page's viewers topic, and that subscription *is* the
  // declaration. This is the assertion that catches a topic string that differs between
  // `KansoEvent.viewersTopic` and `topicsFor` — it would leave this roster empty for ever
  // with no error anywhere.
  await expect(owner.getByTestId("doc-viewer")).toHaveCount(1);
  // `member`, not `E2E member`: `seedInstance` gives a display name only to the owner it
  // claims the instance with, and `member@kanso.test` is provisioned on the spot by
  // `DevAuthenticationFilter` from the local part of its address. Asserted as the fixture
  // actually is rather than as it reads nicely — the point of the assertion is that the
  // roster names *the other person*, and a wrong-but-plausible name would have hidden the
  // handshake bug this test found.
  await expect(owner.getByTestId("doc-viewers")).toHaveAttribute(
    "aria-label",
    "Also reading: member",
  );

  // And neither of them is drawn to themselves: the owner sees one chip, not two.
  await expect(member.getByTestId("doc-viewer")).toHaveCount(1);
  await expect(member.getByTestId("doc-viewers")).toHaveAttribute(
    "aria-label",
    "Also reading: E2E owner",
  );

  // --- the owner takes a block by putting a caret in it ------------------------
  await editorIn(owner, 0).click();

  // The member's screen learns it, again without reloading. The badge names the holder
  // *before* the member has pressed a single key, which is the whole difference between
  // this and a refusal: a paragraph that greys out anonymously is `useReportError`'s bug
  // with better manners, and this repository has shipped that twice.
  const lockBadge = blockAt(member, 0).getByTestId("doc-block-lock");
  await expect(lockBadge).toBeVisible();
  await expect(lockBadge).toHaveAttribute("data-holder", "E2E owner");

  // All three facts, in the label a screen reader announces and a hover shows.
  const notice = (await lockBadge.getAttribute("aria-label")) ?? "";
  expect(notice, "the refusal has to name who holds it").toContain("E2E owner");
  expect(notice, "and say that waiting is a plan").toContain("frees itself");
  expect(notice, "and say how long that is").toMatch(/\d+ s|freeing itself now/);

  // --- and the member cannot type into it --------------------------------------
  const held = editorIn(member, 0);
  await expect(held).toHaveAttribute("readonly", "");
  // Read-only and not disabled: still focusable, still selectable. Somebody waiting for a
  // block wants to read it, and a disabled textarea drops out of the tab order entirely.
  await expect(held).toBeEnabled();
  await held.click();
  await expect(held).toBeFocused();

  // The reorder arrows go with it, on the whole page rather than on this block: `setOrder`
  // rewrites every position, so the server refuses the move — and refusing it visibly,
  // before it is tried, beats a red line after nothing appeared to happen.
  await blockAt(member, 1).hover();
  await expect(blockAt(member, 1).getByRole("button", { name: "Move up" })).toBeDisabled();

  // The owner, meanwhile, is not locked out of their own block.
  await expect(editorIn(owner, 0)).not.toHaveAttribute("readonly", "");
  await expect(blockAt(owner, 0).getByTestId("doc-block-lock")).toHaveCount(0);

  // --- what the second person is told when they lose the race ------------------
  //
  // The block the member had drawn as free, claimed by the owner in between. Reached
  // through the API rather than the screen because that is exactly the race: the screen
  // has no way to offer a keystroke into a field it has already made read-only, and the
  // 409 path still has to say something.
  const memberApi = await apiAs(MEMBER);
  const blockIds = await owner.getByTestId("doc-block").evaluateAll((rows) =>
    rows.map((row) => row.id),
  );
  const refused = await memberApi.patch(`/api/docs/blocks/${blockIds[0]}`, {
    data: { content: { text: "overwritten" } },
  });
  expect(refused.status(), "somebody else's block is a 409, not a 403 and not a 200").toBe(409);
  const problem = (await refused.json()) as { detail: string; holder: string; freesAt: string };
  expect(problem.holder).toBe("E2E owner");
  expect(problem.detail).toContain("E2E owner");
  expect(Date.parse(problem.freesAt), "freesAt is an instant a countdown can be drawn from")
    .not.toBeNaN();

  // --- the edit itself crosses, which is the other half of the ticket ----------
  //
  // Read twice, off the screen and out of the API, for scenario 12's reason: a paragraph
  // that changed and a paragraph that merely repainted look identical, and a failure here
  // has to say *which* of the three steps broke — the typing, the commit, or the crossing.
  const EDIT = "Postgres wins, and both of us can see it";
  await editorIn(owner, 0).fill(EDIT);
  await expect(editorIn(owner, 0), "the typing took").toHaveValue(EDIT);

  await editorIn(owner, 1).click(); // blur commits, and releases the lock

  await expect
    .poll(
      async () => {
        const read = await api.get(`/api/docs/pages/${pageId}`);
        const body = (await read.json()) as { blocks: { content: { text?: string } }[] };
        return body.blocks[0]?.content.text;
      },
      { message: "the commit reached the database" },
    )
    .toBe(EDIT);

  await expect(editorIn(member, 0), "and it crossed to the other browser").toHaveValue(EDIT);

  // The lock moved with the caret: block 0 is free on the member's screen and block 1 is
  // now the held one. A badge that never cleared would be a lock nobody could ever escape.
  await expect(blockAt(member, 0).getByTestId("doc-block-lock")).toHaveCount(0);
  await expect(editorIn(member, 0)).not.toHaveAttribute("readonly", "");
  await expect(blockAt(member, 1).getByTestId("doc-block-lock")).toBeVisible();

  // --- the laptop that closed --------------------------------------------------
  //
  // The owner's tab goes away without releasing anything — no `beforeunload`, no blur, no
  // cooperation of any kind. This is the case the whole expiry design exists for, and the
  // only honest way to test it is to actually destroy the context.
  const shortTtl = await ttlLooksShort(member);
  await owner.context().close();

  // Presence goes first, and it goes because the *socket* went: STOMP's heartbeat tells
  // the server within seconds, and nothing had to be written down or swept.
  await expect(member.getByTestId("doc-viewers")).toHaveCount(0, { timeout: 20_000 });

  if (!shortTtl) {
    // Said out loud rather than skipped silently: a green suite that quietly proved less
    // than it claims is worse than a red one.
    test.info().annotations.push({
      type: "skipped-claim",
      description:
        "The two expiry claims need KANSO_DOCS_LOCK_TTL short (the stack sets 3s). " +
        "On a default 30s instance they would be thirty seconds of wall clock on a " +
        "single-worker suite, so they are not asserted here.",
    });
    return;
  }

  // And the block frees itself, with nothing having run. No sweeper, no heartbeat row: the
  // claim simply stops being one when `expires_at` passes, and the next read says so.
  await expect(blockAt(member, 1).getByTestId("doc-block-lock")).toHaveCount(0, {
    timeout: 20_000,
  });
  await expect(editorIn(member, 1)).not.toHaveAttribute("readonly", "");

  // The member can now write where the owner left off, which is the point of an expiry
  // rather than a lock somebody has to come back and release.
  await editorIn(member, 1).click();
  await editorIn(member, 1).fill("and the lid closed mid-paragraph");
  await editorIn(member, 0).click();
  await expect(editorIn(member, 1)).toHaveValue("and the lid closed mid-paragraph");
});

/**
 * Whether this instance's lock TTL is short enough to watch one expire.
 *
 * Read off the badge the page is already drawing rather than out of the environment: the
 * suite's `KANSO_DOCS_LOCK_TTL` and the *stack's* are two different processes' variables,
 * and trusting the runner's copy is how a test claims to have proved something against a
 * container that never saw it. The countdown is the server's own `freesAt`, so it is the
 * one witness that cannot disagree with the instance under test.
 */
async function ttlLooksShort(page: Page): Promise<boolean> {
  const badge = page.getByTestId("doc-block-lock").first();
  if ((await badge.count()) === 0) return false;
  const seconds = Number(/(\d+) s/.exec((await badge.textContent()) ?? "")?.[1]);
  return Number.isFinite(seconds) && seconds <= 10;
}
