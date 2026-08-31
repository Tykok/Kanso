/*
 * The whole build: one template, two dictionaries, two pages.
 *
 * Dependency-free and framework-free, so the workflow that publishes this has nothing to
 * install and nothing to keep in step with. `node site/build.mjs` is the entire contract.
 *
 * A key the template uses and a dictionary lacks exits non-zero, naming the key and the
 * language. The alternative is a French page that quietly serves an English paragraph —
 * nobody notices that but a reader, and by then it is published. An unused key only
 * warns: a dictionary still carrying a line the template dropped is untidy, not wrong.
 *
 * Dictionary values are substituted as-is, so a value may carry markup (<code>, <a>).
 * They are ours and no reader input reaches them; there is nothing here to escape.
 *
 * No network access, ever — a media host being down must not be able to redden the
 * workflow. The captures live at `media.json`'s `base`, which is a placeholder the owner
 * replaces with the host he serves them from: HTTPS (an http:// image on an HTTPS Pages
 * document is blocked, not degraded), a versioned path so replacing a file cannot serve
 * a stale cache, publicly readable with no signed URL to expire. `--check` HEADs every
 * one of them and is run by hand, never by CI.
 */

import { mkdirSync, readFileSync, writeFileSync } from "node:fs";

const at = (p) => new URL(p, import.meta.url);
const read = (p) => readFileSync(at(p), "utf8");
const load = (p) => JSON.parse(read(p));

const CANONICAL = "https://tykok.github.io/Kanso/";
const REPO = "https://github.com/Tykok/Kanso";

const template = read("template.html");
const media = load("media.json");
const url = (file) => media.base + file;

/* Pages serves this from a subpath, so every href is relative: this is where each page
   sits seen from the other, and `en` doubles as the way back up to the assets. */
const HREF = { en: { en: "./", fr: "./fr/" }, fr: { en: "../", fr: "./" } };

const slot = (step, alt) =>
  step.type === "video"
    ? `<video class="shot" muted loop playsinline preload="none" poster="${url(step.poster)}" aria-label="${alt}"><source src="${url(step.src)}" type="video/mp4"></video>`
    : `<img class="shot" loading="lazy" decoding="async" src="${url(step.src)}" alt="${alt}">`;

function render(lang) {
  const other = lang === "en" ? "fr" : "en";
  const href = HREF[lang];
  const link = (l) =>
    `<a href="${href[l]}" hreflang="${l}"${l === lang ? ' aria-current="page"' : ""}>${l.toUpperCase()}</a>`;
  const dict = load(`i18n/${lang}.json`);
  const vars = {
    ...dict,
    "page.lang": lang,
    "page.rel": href.en,
    "page.canonical": lang === "en" ? CANONICAL : `${CANONICAL}${lang}/`,
    "page.self.href": href[lang],
    "page.other.lang": other,
    "page.other.href": href[other],
    "page.default.href": href.en,
    "page.switch": `${link("en")}<span aria-hidden="true"> · </span>${link("fr")}`,
    "repo.url": REPO,
    "repo.discussions": `${REPO}/discussions`,
    "repo.contributing": `${REPO}/blob/main/CONTRIBUTING.md`,
  };

  const seen = new Set();
  const missing = new Set();
  const take = (key) => {
    seen.add(key);
    if (key in vars) return vars[key];
    missing.add(key);
    return "";
  };

  for (const step of media.steps) vars[`media.${step.key}`] = slot(step, take(`${step.key}.alt`));
  const html = template.replace(/\{\{([\w.-]+)\}\}/g, (_, key) => take(key));

  if (missing.size) {
    console.error(
      `${lang}: site/i18n/${lang}.json is missing ${missing.size} key(s) the template uses: ${[...missing].join(", ")}`,
    );
    process.exit(1);
  }
  for (const key of Object.keys(dict)) {
    if (!seen.has(key)) console.warn(`${lang}: site/i18n/${lang}.json declares "${key}", which the template never uses`);
  }
  return html;
}

/* Both pages are rendered before anything is written, so a missing key leaves the previous
   dist/ as it was rather than half of this one. */
const pages = { "index.html": render("en"), "fr/index.html": render("fr") };
const write = (p, body) => writeFileSync(at(`../dist/${p}`), body);
mkdirSync(at("../dist/fr"), { recursive: true });
write("tokens.css", read("../apps/web/src/styles/tokens.css"));
write("style.css", read("style.css"));
for (const [p, body] of Object.entries(pages)) write(p, body);
console.log("dist/index.html, dist/fr/index.html, dist/tokens.css, dist/style.css");

if (process.argv.includes("--check")) {
  for (const step of media.steps) {
    for (const file of [step.src, step.poster].filter(Boolean)) {
      const res = await fetch(url(file), { method: "HEAD" }).catch((err) => ({
        ok: false,
        status: err.cause?.code ?? err.code ?? "no response",
      }));
      console.log(`${res.ok ? "ok         " : "UNREACHABLE"} ${res.status}  ${url(file)}`);
    }
  }
}
