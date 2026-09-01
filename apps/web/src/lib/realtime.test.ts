import { beforeEach, describe, expect, it, vi } from "vitest";
import { connectRealtime } from "./realtime";

/**
 * The STOMP client, replaced by one a test can drive by hand.
 *
 * A dropped socket is the one thing this module exists to survive and the one thing a
 * browser will not do on request — so the honest proof is here rather than in a
 * screenshot. `connectRealtime` installs `onConnect` and `onWebSocketClose` on the
 * client it builds; the fake hands those back, and a test calls them in the order an
 * outage produces them.
 *
 * Hoisted because `vi.mock`'s factory runs when `./realtime` is imported, which is
 * before this file's own top-level bindings exist.
 */
const stomp = vi.hoisted(() => {
  class FakeClient {
    onConnect: () => void = () => {};
    onWebSocketClose: () => void = () => {};
    active = false;
    readonly subscribed: string[] = [];
    readonly unsubscribed: string[] = [];

    constructor(readonly config: Record<string, unknown>) {
      clients.push(this);
    }

    activate() {
      this.active = true;
    }

    deactivate() {
      this.active = false;
    }

    subscribe(topic: string) {
      this.subscribed.push(topic);
      return { unsubscribe: () => void this.unsubscribed.push(topic) };
    }
  }

  const clients: FakeClient[] = [];
  return { clients, FakeClient };
});

vi.mock("@stomp/stompjs", () => ({ Client: stomp.FakeClient }));

/** The client `connectRealtime` most recently built. */
const socket = () => stomp.clients[stomp.clients.length - 1];

beforeEach(() => {
  stomp.clients.length = 0;
});

describe("a socket that comes back", () => {
  it("says nothing about the first connection, which missed nothing", () => {
    const resumed = vi.fn();

    connectRealtime({ onEvent: () => {}, onResume: resumed });
    socket().onConnect();

    // The queries loaded at mount, alongside this handshake. Calling it a resume would
    // buy a second copy of every one of them on every page load.
    expect(resumed).not.toHaveBeenCalled();
  });

  it("reports the gap once a socket that was away comes back", () => {
    const resumed = vi.fn();

    connectRealtime({ onEvent: () => {}, onResume: resumed });
    socket().onConnect();
    socket().onWebSocketClose();
    socket().onConnect();

    expect(resumed).toHaveBeenCalledTimes(1);
  });

  it("reports every outage, not only the first", () => {
    const resumed = vi.fn();

    connectRealtime({ onEvent: () => {}, onResume: resumed });
    socket().onConnect();
    for (let outage = 0; outage < 3; outage += 1) {
      socket().onWebSocketClose();
      socket().onConnect();
    }

    expect(resumed).toHaveBeenCalledTimes(3);
  });

  it("says nothing while the socket is still down, however long it stays down", () => {
    const resumed = vi.fn();

    connectRealtime({ onEvent: () => {}, onResume: resumed });
    socket().onConnect();
    socket().onWebSocketClose();
    socket().onWebSocketClose();

    // The refresh is what a reader can see again, so it belongs to coming back, not to
    // going away: firing it at a tab with no connection would refetch into the dark.
    expect(resumed).not.toHaveBeenCalled();
  });

  it("asks the new broker for the topics the screen still wants", () => {
    const connection = connectRealtime({ onEvent: () => {}, onResume: () => {} });
    connection.subscribeTo(["/topic/teams", "/topic/teams/team-a/tickets"]);
    socket().onConnect();
    socket().onWebSocketClose();
    socket().onConnect();

    // A reconnect gets a broker that has forgotten every subscription, so the resume has
    // to be a re-subscribe and not only an invalidation.
    expect(socket().subscribed).toEqual([
      "/topic/teams",
      "/topic/teams/team-a/tickets",
      "/topic/teams",
      "/topic/teams/team-a/tickets",
    ]);
  });
});
