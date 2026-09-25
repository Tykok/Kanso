/**
 * Where `See the queue` on a refused push goes. The queue section is the configurator's;
 * a member lands on the connections card, which still says how many writes were refused.
 */
export const syncQueueHref = (canConfigure: boolean) =>
  canConfigure ? "/settings?section=sync-queue" : "/settings?section=connections";
