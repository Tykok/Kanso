-- Finish-to-start dependencies between tickets. Two columns and nothing else:
-- no lag, no link type. Both are cheap, non-destructive additions the day one is
-- actually asked for, and neither has been.
--
-- Dependencies deliberately cross teams. The neighbouring rule is the opposite —
-- a ticket's project must belong to its team — because a project is a container
-- and a dependency is not.
CREATE TABLE ticket_dependencies (
  predecessor_id UUID NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
  successor_id   UUID NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (predecessor_id, successor_id),
  CHECK (predecessor_id <> successor_id)
);

-- The primary key already indexes (predecessor_id, successor_id); walking the
-- graph backwards needs the other direction.
CREATE INDEX ticket_dependencies_successor_idx ON ticket_dependencies (successor_id);
