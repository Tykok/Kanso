import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// Testing Library installs its own auto-cleanup only when `afterEach` is a global, and
// this repo runs vitest without `globals`. Left out, a tree from one test stays mounted
// into the next — and these tests count renders, so a leftover tree is a wrong answer
// rather than merely a slow one.
afterEach(cleanup);
