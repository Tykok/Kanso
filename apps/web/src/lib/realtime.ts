import { Client, type IMessage } from "@stomp/stompjs";
import { API_URL } from "./api";

export type ChangeKind = "CREATED" | "UPDATED" | "DELETED";

export type KansoEvent = {
  entity: "tickets" | "projects" | "teams";
  kind: ChangeKind;
  id: string;
  teamId?: string;
  projectId?: string;
  /** "kanso" for someone's keystroke, "notion" when the inbound poller applied it. */
  origin: "kanso" | "notion";
  at: string;
};

/**
 * Subscribes to the server's change feed.
 *
 * Events carry only an id and enough scope to decide whether a view cares — the
 * receiver refetches. Shipping whole entities over the bus would mean two
 * definitions of their shape, and the payload has to fit in a Postgres
 * notification either way.
 */
export function connectRealtime(onEvent: (event: KansoEvent) => void): () => void {
  const url = API_URL.replace(/^http/, "ws") + "/ws";

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

  client.onConnect = () => {
    for (const topic of ["tickets", "projects", "teams"]) {
      client.subscribe(`/topic/${topic}`, handle);
    }
  };

  client.activate();
  return () => {
    void client.deactivate();
  };
}
