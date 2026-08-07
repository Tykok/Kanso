"use client";

import { keys, useSetupState } from "@/lib/queries";

/**
 * The wizard reads the same setup state as the routing guard — one definition, one
 * cache entry. Re-exported here so the setup components keep a single local import
 * and do not each reach into lib/queries.
 */
export const setupKeys = { state: keys.setupState };

export { useSetupState };
