CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE IF NOT EXISTS technicians (
    username text PRIMARY KEY,
    display_name text NOT NULL,
    skill text NOT NULL,
    available_from timestamptz NOT NULL,
    available_to timestamptz NOT NULL,
    location geography(Point, 4326) NOT NULL,
    max_distance_km integer NOT NULL CHECK (max_distance_km > 0)
);
CREATE INDEX IF NOT EXISTS technicians_location_gix ON technicians USING gist (location);

CREATE TABLE IF NOT EXISTS service_requests (
    id uuid PRIMARY KEY,
    customer_username text NOT NULL,
    client_request_key text NOT NULL,
    request_fingerprint text NOT NULL,
    problem text NOT NULL,
    skill text NOT NULL,
    location geography(Point, 4326) NOT NULL,
    window_start timestamptz NOT NULL,
    window_end timestamptz NOT NULL,
    status text NOT NULL,
    technician_username text REFERENCES technicians(username),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (customer_username, client_request_key)
);
CREATE INDEX IF NOT EXISTS requests_location_gix ON service_requests USING gist (location);
CREATE INDEX IF NOT EXISTS requests_technician_window_idx ON service_requests (technician_username, window_start, window_end);

CREATE TABLE IF NOT EXISTS status_history (
    id bigserial PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES service_requests(id) ON DELETE CASCADE,
    status text NOT NULL,
    actor_username text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS status_history_request_idx ON status_history (request_id, id);

CREATE TABLE IF NOT EXISTS payments (
    request_id uuid PRIMARY KEY REFERENCES service_requests(id) ON DELETE CASCADE,
    stripe_session_id text UNIQUE NOT NULL,
    checkout_url text NOT NULL,
    status text NOT NULL,
    amount_cents integer NOT NULL,
    currency text NOT NULL,
    paid_at timestamptz
);

CREATE TABLE IF NOT EXISTS stripe_events (
    event_id text PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES service_requests(id),
    received_at timestamptz NOT NULL DEFAULT now()
);
