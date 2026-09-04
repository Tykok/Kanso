import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
  ]),
  {
    /**
     * `react-hooks/incompatible-library` is off, and this is the one rule here that is
     * silenced rather than obeyed.
     *
     * It fires on every `useVirtualizer()` — four of them, one per virtualised surface —
     * to say that TanStack Virtual returns functions React Compiler will not memoize, so
     * it skips memoizing the component. That is the correct outcome and not a defect: the
     * alternative is memoizing them and serving stale rows. There is nothing to change at
     * the call sites short of dropping the library, so the rule cannot be obeyed, only
     * lived with.
     *
     * Off here rather than four `eslint-disable-next-line`s, because those live in four
     * files owned by four different screens and each would have to be re-argued by
     * whoever next edits one. Off globally is a decision taken once, in the place that
     * takes decisions about the lint. What it costs is real: a genuinely incompatible
     * library adopted later goes unremarked. That is the trade, and it is worth it
     * against a lint that printed seven warnings every run and so was read by nobody —
     * which is how the two dead `useState`s in the composer survived in the same output.
     */
    rules: { "react-hooks/incompatible-library": "off" },
  },
]);

export default eslintConfig;
