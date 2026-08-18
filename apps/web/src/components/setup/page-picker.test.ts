import { describe, expect, it } from "vitest";
import { pageIdFrom, pageLabel, pickerNotice, sameId } from "./page-picker";

describe("pageLabel", () => {
  it("names a page by its title", () => {
    expect(pageLabel({ id: "2f1a4c99", title: "Engineering" })).toBe("Engineering");
  });

  it("names an untitled page by the head of its id, because it still has to be pickable", () => {
    // Notion allows a page with no title. Hiding it would mean the page somebody just
    // shared is missing from the list with no explanation, which is the failure the
    // picker exists to remove; "Untitled" alone would make two of them the same row.
    expect(pageLabel({ id: "2f1a4c99b0d24e1a8f0e5c7d3a1b2c4d" })).toBe("Untitled page · 2f1a4c99");
  });

  it("treats a blank title as no title at all", () => {
    expect(pageLabel({ id: "2f1a4c99b0d2", title: "   " })).toBe("Untitled page · 2f1a4c99");
  });
});

describe("pickerNotice", () => {
  const page = { id: "2f1a4c99", title: "Engineering" };

  it("says nothing while there is a list to draw", () => {
    expect(pickerNotice({ answer: { available: true, pages: [page] } })).toBeNull();
  });

  it("says nothing while it is still reading", () => {
    expect(pickerNotice({ loading: true })).toBeNull();
  });

  it("prints the server's own sentence when there is no workspace to search", () => {
    const notice = pickerNotice({
      answer: { available: false, reason: "No Notion token is configured.", pages: [] },
    });

    expect(notice?.body).toContain("No Notion token is configured.");
  });

  it("diagnoses an empty list instead of drawing an empty list", () => {
    // The whole feature, in one sentence. An empty list means the integration exists and
    // nobody has shared a page with it — exactly the silent failure the pasted id hid
    // until bootstrap. So it names Notion's own menu, says the list can be reloaded, and
    // admits that a page shared seconds ago may not be searchable yet.
    const notice = pickerNotice({ answer: { available: true, pages: [] } });

    expect(notice).not.toBeNull();
    expect(notice?.body).toContain("Connections");
    expect(notice?.body).toContain("•••");
    expect(notice?.body.toLowerCase()).toContain("reload");
    expect(notice?.body.toLowerCase()).toContain("by hand");
  });

  it("reports a failed request as itself, not as an empty workspace", () => {
    const notice = pickerNotice({ error: "No answer from the API." });

    expect(notice?.body).toContain("No answer from the API.");
  });
});

describe("pageIdFrom", () => {
  it("takes the id out of a pasted page URL, so the fallback is not a slicing exercise", () => {
    expect(pageIdFrom("https://www.notion.so/acme/Engineering-2f1a4c99b0d24e1a8f0e5c7d3a1b2c4d")).toBe(
      "2f1a4c99b0d24e1a8f0e5c7d3a1b2c4d",
    );
  });

  it("keeps a dashed id, which is what the API itself answers", () => {
    expect(pageIdFrom("2f1a4c99-b0d2-4e1a-8f0e-5c7d3a1b2c4d")).toBe("2f1a4c99-b0d2-4e1a-8f0e-5c7d3a1b2c4d");
  });

  it("drops a query string a copied URL brings with it", () => {
    expect(pageIdFrom("https://notion.so/2f1a4c99b0d24e1a8f0e5c7d3a1b2c4d?pvs=4")).toBe(
      "2f1a4c99b0d24e1a8f0e5c7d3a1b2c4d",
    );
  });

  it("leaves half-typed input alone rather than fighting the person typing it", () => {
    expect(pageIdFrom("  2f1a4c ")).toBe("2f1a4c");
  });
});

describe("sameId", () => {
  it("matches the dashed id Notion answers against the bare one somebody pasted", () => {
    // Without this the picker could never show a page that is already saved as selected,
    // and every instance set up before the picker existed would look unconfigured.
    expect(sameId("2f1a4c99-b0d2-4e1a-8f0e-5c7d3a1b2c4d", "2f1a4c99b0d24e1a8f0e5c7d3a1b2c4d")).toBe(true);
  });

  it("ignores the case Notion URLs sometimes carry", () => {
    expect(sameId("2F1A4C99B0D24E1A8F0E5C7D3A1B2C4D", "2f1a4c99b0d24e1a8f0e5c7d3a1b2c4d")).toBe(true);
  });

  it("is false for two different pages, and for nothing at all", () => {
    expect(sameId("2f1a4c99b0d24e1a8f0e5c7d3a1b2c4d", "aaaaaaaabbbbccccddddeeeeeeeeeeee")).toBe(false);
    expect(sameId("", "")).toBe(false);
  });
});
