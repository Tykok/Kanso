/**
 * The writes made while the network was gone, on disk.
 *
 * On disk is the whole point. A queue in memory is indistinguishable from a working
 * one until the tab is reloaded, at which point the writes it was holding are gone
 * with no trace that they ever existed — and the reader has already been told "you can
 * keep writing". So the store is the queue: this class holds no list of its own, reads
 * every answer back off the disk, and can be thrown away and rebuilt between any two
 * calls without changing what happens next. The tests do exactly that.
 *
 * The disk is behind [QueueStore] so that the behaviour worth testing — the order, the
 * survival, what becomes of a refusal — is testable in `environment: "node"`, where
 * there is no IndexedDB. `idb.ts` is the browser's implementation of this one
 * interface and holds no logic.
 */

/** A write waiting to be sent, exactly as it is stored. */
export type QueuedWrite = {
  id: string;
  /**
   * Monotonic across the whole disk, handed out from whatever is already stored.
   *
   * Per device rather than per actor, although the guarantee is per actor: one counter
   * gives ascending order inside every actor's chain for free, and it also fixes the
   * order the chains themselves are attempted in — two counters could tie, and a tie
   * is a send order that depends on how a `Map` happened to be built.
   */
  seq: number;
  /** Whose writes these are. Dev mode lets one browser be several people. */
  actor: string;
  /** `KAN-142` — what the banner's row prints in its first column. */
  reference: string;
  /** `status → In review`. Written when the write is made, not derived on read. */
  summary: string;
  request: { path: string; method: string; body?: unknown };
  /**
   * `queued` while it may still go, `rejected` once the server has said no.
   *
   * The difference is not cosmetic: a chain stops at either, but a `rejected` write
   * needs somebody to decide something (retry it, or discard it), and a `queued` one
   * needs nothing but a network. A flush that could not reach the server at all must
   * leave the whole queue `queued`, or an outage would fill the banner with refusals
   * nobody has to act on.
   *
   * There is no `sending`.
   *
   * A third state would have to be written to disk before the request goes out, and a
   * tab closed mid-flight would then leave a row nothing ever moves off `sending` —
   * a chain jammed by a crash, needing a repair pass on startup to unjam. The cost of
   * not having it is that a request whose response was lost may be sent twice; the
   * cost of having it is a queue that silently stops. Kanso's writes are whole-row
   * upserts, so the first is survivable and the second is not.
   */
  state: "queued" | "rejected";
  /** Why the server refused it. Kept: a rejected write is not a lost one. */
  error?: string;
  queuedAt: string;
};

/** What the queue needs of a disk, and nothing more. */
export type QueueStore = {
  all(): Promise<QueuedWrite[]>;
  put(write: QueuedWrite): Promise<void>;
  remove(id: string): Promise<void>;
};

export type FlushResult = {
  sent: number;
  /** Refused by a server that answered. Each needs a decision. */
  rejected: number;
  /** Chains that stopped because nothing answered. Nothing to decide, only to wait. */
  unreachable: number;
  /** Still on disk afterwards — what the banner counts. */
  remaining: number;
};

type Options = {
  send: (write: QueuedWrite) => Promise<void>;
  actor: string;
  /**
   * Whether a thrown error means the server answered.
   *
   * Injected rather than sniffed here: what an answered request looks like is the
   * client's business (`lib/api` throws `ApiError` for every one), and this module has
   * no import from it. Defaults to true, which is the conservative reading — a failure
   * of unknown origin is treated as a refusal and stops asking.
   */
  answered?: (error: unknown) => boolean;
  now?: () => Date;
  newId?: () => string;
};

export class OfflineQueue {
  private readonly store: QueueStore;
  private readonly options: Required<Options>;

  /**
   * The flush in flight, if any.
   *
   * The banner flushes when the browser comes back online and a mutation flushes when
   * it lands, and both can fire in the same tick. Two flushes reading the same disk
   * would each see the same `queued` row and send it twice, so the second caller joins
   * the first instead of starting a second pass.
   */
  private inFlight?: Promise<FlushResult>;

  constructor(store: QueueStore, options: Options) {
    this.store = store;
    this.options = {
      now: () => new Date(),
      newId: () => globalThis.crypto.randomUUID(),
      answered: () => true,
      ...options,
    };
  }

  /** This actor's writes, oldest first — the order the banner lists them in. */
  async list(): Promise<QueuedWrite[]> {
    const rows = await this.store.all();
    return rows.filter((write) => write.actor === this.options.actor).sort(bySeq);
  }

  async enqueue(input: {
    reference: string;
    summary: string;
    request: QueuedWrite["request"];
  }): Promise<QueuedWrite> {
    const rows = await this.store.all();
    const write: QueuedWrite = {
      id: this.options.newId(),
      seq: rows.reduce((highest, row) => Math.max(highest, row.seq), 0) + 1,
      actor: this.options.actor,
      reference: input.reference,
      summary: input.summary,
      request: input.request,
      state: "queued",
      queuedAt: this.options.now().toISOString(),
    };
    await this.store.put(write);
    return write;
  }

  /**
   * Sends everything sendable, one chain at a time.
   *
   * A chain is one actor's writes in the order they were made, and it stops at the
   * first write that does not go through — including one already sitting `rejected`
   * from a previous flush. That is the ordering guarantee doing its job rather than
   * failing to: the writes behind a refused one were made against the row that write
   * was supposed to produce, so sending them would apply a change to a state that
   * never existed. Another actor's chain is unaffected; it is a different row's story.
   */
  flush(): Promise<FlushResult> {
    this.inFlight ??= this.drain().finally(() => {
      this.inFlight = undefined;
    });
    return this.inFlight;
  }

  private async drain(): Promise<FlushResult> {
    const rows = (await this.store.all()).sort(bySeq);
    let sent = 0;
    let rejected = 0;
    let unreachable = 0;

    // Grouped in `seq` order, so the chains are attempted in the order they were
    // started rather than in whatever order the disk enumerated them.
    const chains = new Map<string, QueuedWrite[]>();
    for (const write of rows) {
      const chain = chains.get(write.actor);
      if (chain) chain.push(write);
      else chains.set(write.actor, [write]);
    }

    for (const chain of chains.values()) {
      for (const write of chain) {
        if (write.state !== "queued") break;
        try {
          await this.options.send(write);
          await this.store.remove(write.id);
          sent++;
        } catch (error) {
          if (this.options.answered(error)) {
            await this.store.put({ ...write, state: "rejected", error: reasonOf(error) });
            rejected++;
          } else {
            // Left exactly as it was, including its state: the network is what failed,
            // and nothing about the write has been decided. Writing an error onto it
            // would fill the banner with refusals nobody has to act on.
            unreachable++;
          }
          break;
        }
      }
    }

    return { sent, rejected, unreachable, remaining: (await this.store.all()).length };
  }

  /** Puts a refused write back in line, at the position it always had. */
  async retry(id: string): Promise<void> {
    const write = (await this.store.all()).find((candidate) => candidate.id === id);
    if (!write) return;
    await this.store.put({ ...write, state: "queued", error: undefined });
  }

  /**
   * The only way a write leaves the queue unsent, and it is always asked for. A queue
   * that drops what it cannot send is a queue that lies about having held it.
   */
  async discard(id: string): Promise<void> {
    await this.store.remove(id);
  }
}

const bySeq = (a: QueuedWrite, b: QueuedWrite) => a.seq - b.seq;

/**
 * The sentence to keep beside a refused write. Deliberately not
 * `actionErrorMessage`: this string is written to disk and read back weeks later, and
 * the queue must not depend on how the interface words a failure today.
 */
function reasonOf(error: unknown): string {
  if (error instanceof Error && error.message) return error.message;
  return "The server refused this write";
}
