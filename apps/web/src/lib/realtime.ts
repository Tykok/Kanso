import { Client, type IMessage, type StompSubscription } from "@stomp/stompjs";
import { apiOrigin, getDevUser } from "./api";
import type { KansoEvent } from "./realtime-events";

// The wire format lives beside the code that decides what an event means, and is
// re-exported here so a caller that only needs the socket still gets the shape it
// receives.
export type { ChangeKind, KansoEvent } from "./realtime-events";

/**
 * A live connection: one socket, and a set of topics that changes under it.
 *
 * Separate because they have different lifetimes. The socket is per session — a
 * handshake, a session cookie and a reconnect loop — while the topics are per screen and
 * change every time someone scopes the list to another team. Tearing down the first to
 * change the second would spend a round trip to move a `SUBSCRIBE` frame.
 */
export type RealtimeConnection = {
  subscribeTo(topics: readonly string[]): void;
  close(): void;
};

export type RealtimeHandlers = {
  onEvent: (event: KansoEvent) => void;
  /**
   * A socket that was away is back, and the events of the outage are gone.
   *
   * Separate from [onEvent] because it is the opposite kind of news: an event says what
   * changed, and this says that something did and nobody knows what. What to do about it
   * is `resumeAfterOutage`'s answer, not this module's — the socket's job ends at
   * noticing the gap.
   */
  onResume: () => void;
};

/**
 * Subscribes to the server's change feed.
 *
 * Events carry only an id and enough scope to decide whether a view cares — the
 * receiver patches or refetches. Shipping whole entities over the bus would mean two
 * definitions of their shape, and the payload has to fit in a Postgres notification
 * either way.
 *
 * Opens listening to nothing: which topics to ask for is [topicsFor]'s answer, not this
 * function's, and the caller says so through [RealtimeConnection.subscribeTo] — the same
 * call it makes every time the scope changes, rather than a special first one.
 */
export function connectRealtime({ onEvent, onResume }: RealtimeHandlers): RealtimeConnection {
  /**
   * The dev identity, on the URL, because a WebSocket handshake cannot carry a header.
   *
   * `new WebSocket(url)` takes a URL and nothing else — by specification — so the
   * `X-Kanso-User` header that `request()` attaches to every fetch has no equivalent here.
   * Under a cookie login that costs nothing: the same cookie authenticates the handshake.
   * In dev mode it meant every socket in the instance authenticated as `dev@kanso.local`,
   * so every browser was the *same person* on the bus.
   *
   * Invisible until `KAN-25`, because nothing had ever depended on who a socket belonged
   * to — events are broadcast to topics, not to people. Presence does: it is derived from
   * the socket itself, and two people on one document showed as one viewer called "dev".
   *
   * Absent outside dev mode, where `getDevUser()` is null and the cookie is the credential.
   */
  const devUser = getDevUser();
  /**
   * [apiOrigin] and not `API_URL`, which is the empty string wherever nothing was inlined:
   * `"".replace(/^http/, "ws")` is still `""`, so this would dial `/ws`, and that is the
   * one caller a relative path cannot serve — `new WebSocket` rejects it where `fetch`
   * resolves it. The guard inside `apiOrigin` is inert here: a socket is dialled from a
   * browser by construction.
   */
  const url =
    apiOrigin().replace(/^http/, "ws") +
    "/ws" +
    (devUser ? `?devUser=${encodeURIComponent(devUser)}` : "");

  const client = new Client({
    brokerURL: url,
    // The handshake carries the session cookie, so no credentials here.
    reconnectDelay: 2000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
  });

  const handle = (message: IMessage) => {
    try {
      onEvent(JSON.parse(message.body) as KansoEvent);
    } catch {
      // A malformed frame is not worth breaking the session over.
    }
  };

  const active = new Map<string, StompSubscription>();
  let wanted: readonly string[] = [];
  let connected = false;

  const reconcile = () => {
    if (!connected) return;
    for (const [topic, subscription] of active) {
      if (!wanted.includes(topic)) {
        subscription.unsubscribe();
        active.delete(topic);
      }
    }
    for (const topic of wanted) {
      if (!active.has(topic)) active.set(topic, client.subscribe(topic, handle));
    }
  };

  // Whether a socket has ever been up. What tells a reconnect from the first connect —
  // and the first connect is not a resume: the queries load at mount alongside it, so
  // announcing a gap there would buy a second copy of every one of them on every load.
  let established = false;

  client.onConnect = () => {
    connected = true;
    // A reconnect gets a broker that has forgotten every subscription, so the handles
    // from the previous socket are dropped rather than reused.
    active.clear();
    reconcile();
    // After the re-subscribe, so the topics are live before anything acts on the gap:
    // whatever the resume refetches would otherwise race the frames it is refetching for.
    if (established) onResume();
    established = true;
  };
  client.onWebSocketClose = () => {
    connected = false;
    active.clear();
  };

  client.activate();

  return {
    subscribeTo: (next) => {
      wanted = next;
      reconcile();
    },
    close: () => void client.deactivate(),
  };
}
