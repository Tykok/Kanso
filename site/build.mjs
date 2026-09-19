/*
 * The whole build: two shells, a dictionary per page per language, fourteen pages.
 *
 * Dependency-free and framework-free, so the workflow that publishes this has nothing to
 * install and nothing to keep in step with. `node site/build.mjs` is the entire contract.
 *
 * A key a template uses and a dictionary lacks exits non-zero, naming the key, the page
 * and the language. The alternative is a French page that quietly serves an English
 * paragraph — nobody notices that but a reader, and by then it is published. An unused key
 * only warns, and it is checked per *language* rather than per page: the shell's chrome
 * lives in the landing dictionary and is read by thirteen other pages, so a per-page check
 * would call every landing key unused six times over.
 *
 * Dictionary values are substituted as-is, so a value may carry markup (<code>, <a>).
 * They are ours and no reader input reaches them; there is nothing here to escape.
 *
 * Commands are *not* in the dictionaries. `docker compose up` is the same string in both
 * languages, and a translated copy of it is a copy that can drift from the one the reader
 * of the other page is given. Prose is translated; shell lines are written once, in the
 * template, where both languages read the same characters.
 *
 * No network access, ever — a media host being down must not be able to redden the
 * workflow. The captures are `site/media/v1/`, copied into `dist/` below and served from
 * `media.json`'s `base`, which is this site's own Pages URL: HTTPS (an http:// image on
 * an HTTPS Pages document is blocked, not degraded), a versioned path so replacing a file
 * cannot serve a stale cache, publicly readable with no signed URL to expire. That the
 * host is now us changes none of those four, and `--check` still HEADs every one of them
 * by hand, never in CI — it is the deployed page it asks about, not the local `dist/`.
 */

import { cpSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";

const at = (p) => new URL(p, import.meta.url);
const read = (p) => readFileSync(at(p), "utf8");
const load = (p) => JSON.parse(read(p));

const CANONICAL = "https://tykok.github.io/Kanso/";
const REPO = "https://github.com/Tykok/Kanso";
const LANGS = ["en", "fr"];

/* The wiki, in reading order — which is also the order of the column and of the pager at
   the foot of each page. A slug is the directory it is served from, the name of its body
   template and the stem of its two dictionaries, so a new page is three files and one
   line here. Order is meaning: `model` before `working` because the second page uses the
   words the first one defines. */
const WIKI = ["get-started", "model", "working", "notion", "agents", "self-hosting"];

/* Every page the site has. The landing page is the one that carries its own whole
   template; a wiki page carries only its body and is wrapped by `wiki/shell.html`. */
const PAGES = [
  { path: "index.html" },
  ...WIKI.map((slug) => ({ path: `docs/${slug}/index.html`, slug })),
];

const template = read("template.html");
const shell = read("wiki/shell.html");
const bodies = Object.fromEntries(WIKI.map((slug) => [slug, read(`wiki/${slug}.html`)]));
const media = load("media.json");
const url = (file) => media.base + file;

/* The directory a page is served from, seen from the site root: "" for the landing page,
   "docs/model/" for a wiki page. What a link has to point at, since no href in a published
   page ever names `index.html`. */
const dirOf = (path) => path.replace(/index\.html$/, "");

const slot = (step, alt) =>
  step.type === "video"
    ? `<video class="shot" muted loop playsinline preload="none" poster="${url(step.poster)}" aria-label="${alt}"><source src="${url(step.src)}" type="video/mp4"></video>`
    : `<img class="shot" loading="lazy" decoding="async" src="${url(step.src)}" alt="${alt}">`;

/* Pages serves this from a subpath and the wiki adds two directory levels, so every href
   is relative and every page computes its own way back up. `up` doubles as the path to the
   stylesheets, which sit at the root next to the English landing page. */
function render(page, lang, seen, problems) {
  const out = lang === "en" ? page.path : `${lang}/${page.path}`;
  const up = "../".repeat(out.split("/").length - 1);
  const href = (path, l) => {
    if (path === page.path && l === lang) return "./";
    return `${up}${l === "en" ? "" : `${l}/`}${dirOf(path)}` || "./";
  };

  const dict = load(`i18n/${lang}.json`);
  const vars = {
    ...dict,
    ...(page.slug ? load(`wiki/${page.slug}.${lang}.json`) : {}),
    "page.lang": lang,
    "page.rel": up || "./",
    "page.canonical": `${CANONICAL}${lang === "en" ? "" : `${lang}/`}${dirOf(page.path)}`,
    "page.self.href": href(page.path, lang),
    "page.other.lang": lang === "en" ? "fr" : "en",
    "page.other.href": href(page.path, lang === "en" ? "fr" : "en"),
    "page.default.href": href(page.path, "en"),
    "page.switch": LANGS.map(
      (l) =>
        `<a href="${href(page.path, l)}" hreflang="${l}"${l === lang ? ' aria-current="page"' : ""}>${l.toUpperCase()}</a>`,
    ).join('<span aria-hidden="true"> · </span>'),
    "home.href": href("index.html", lang),
    "repo.url": REPO,
    "repo.discussions": `${REPO}/discussions`,
    "repo.contributing": `${REPO}/blob/main/CONTRIBUTING.md`,
  };
  /* Every page can link to every other by name, which is what keeps a cross-reference from
     hard-coding a depth that is wrong the day the page moves. */
  for (const slug of WIKI) vars[`href.${slug}`] = href(`docs/${slug}/index.html`, lang);
  vars["docs.href"] = vars[`href.${WIKI[0]}`];

  const missing = new Set();
  const take = (key) => {
    seen.add(key);
    if (key in vars) return vars[key];
    missing.add(key);
    return "";
  };

  if (!page.slug)
    for (const step of media.steps) vars[`media.${step.key}`] = slot(step, take(`${step.key}.alt`));

  /* The column and the pager are built here rather than written out in the shell: they are
     the same list six times, and six hand-written copies is six places to forget a page. */
  if (page.slug) {
    const i = WIKI.indexOf(page.slug);
    const row = (slug) =>
      `<a href="${href(`docs/${slug}/index.html`, lang)}"${slug === page.slug ? ' aria-current="page"' : ""}>${take(`nav.${slug}`)}</a>`;
    const step = (slug, dir) =>
      slug
        ? `<a class="pager-${dir}" href="${href(`docs/${slug}/index.html`, lang)}"><span class="meta">${take(`pager.${dir}`)}</span>${take(`nav.${slug}`)}</a>`
        : "";
    vars["doc.nav"] = WIKI.map(row).join("");
    vars["doc.prev"] = step(WIKI[i - 1], "prev");
    vars["doc.next"] = step(WIKI[i + 1], "next");
  }

  const source = page.slug ? shell.replace("{{doc.body}}", bodies[page.slug]) : template;
  const html = source.replace(/\{\{([\w.-]+)\}\}/g, (_, key) => take(key));

  if (missing.size)
    problems.push(`${out}: ${missing.size} key(s) missing in ${lang}: ${[...missing].join(", ")}`);
  return [out, html];
}

/* Every page is rendered before anything is written, so one missing key leaves the previous
   dist/ as it was rather than half of this one. */
const pages = [];
const problems = [];
for (const lang of LANGS) {
  const seen = new Set();
  for (const page of PAGES) pages.push(render(page, lang, seen, problems));
  const dicts = [`i18n/${lang}.json`, ...WIKI.map((slug) => `wiki/${slug}.${lang}.json`)];
  for (const file of dicts)
    for (const key of Object.keys(load(file)))
      if (!seen.has(key)) console.warn(`site/${file} declares "${key}", which no template uses`);
}

if (problems.length) {
  for (const problem of problems) console.error(problem);
  process.exit(1);
}

const write = (p, body) => {
  mkdirSync(at(`../dist/${p}`.replace(/\/[^/]*$/, "")), { recursive: true });
  writeFileSync(at(`../dist/${p}`), body);
};
mkdirSync(at("../dist"), { recursive: true });
write("tokens.css", read("../apps/web/src/styles/tokens.css"));
write("style.css", read("style.css"));
for (const [p, body] of pages) write(p, body);

/* The captures, byte for byte. Copied rather than read and rewritten because they are
   binary, and copied as a whole directory rather than per `media.steps` entry: a `poster`
   a future video adds would otherwise have to be remembered here too, and the directory
   holds nothing else. It is not optional — `base` points inside `dist/`, so a run that
   skipped this would publish five URLs that 404 on our own domain rather than on
   somebody's. Recursive, so `v1/` survives as `v1/`; `force` so a second run overwrites. */
cpSync(at("media/v1"), at("../dist/media/v1"), { recursive: true, force: true });
console.log(`dist/: ${pages.length} pages, tokens.css, style.css, media/v1/`);

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
