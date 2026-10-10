# Architecture notes

## Data and trust boundaries

The Next.js portal and React Native client are public OIDC clients. They obtain access tokens with authorization code + PKCE. Spring Security validates JWT issuer, signature, and expiry; the API maps `realm_access.roles` to application roles and uses `preferred_username` as the demo account identifier. Clients do not choose the acting role or owner of a request.

The API owns service requests, technician profiles, status history, and payment records in PostgreSQL. Locations are stored as PostGIS `geography(Point, 4326)` so distance and radius predicates operate in meters. Reads apply ownership/assignment checks before returning a request or its history. Only operators can ask for candidates or assign work.

## Dispatch consistency

The eligibility query requires matching skill, a time window contained by the technician's available interval, a request inside the technician's radius, and no overlapping active assignment. Completed work no longer blocks that technician. During assignment, the API locks the request row and then the technician row before repeating the eligibility query. This makes concurrent assignments for one technician serialize; the second transaction observes the first assignment and receives 409 if the windows overlap.

Status transitions are a fixed directed path. The assigned technician alone can advance it. Repeating the current state is safe; skipping or reversing a state is rejected. Every accepted change appends one history event in the same database transaction as the current-state update.

## Retry and payment model

The customer sends an `Idempotency-Key`. The database enforces uniqueness per customer. A SHA-256 fingerprint of normalized input distinguishes a network retry from accidental key reuse with changed content. Stripe Checkout creation uses a deterministic Stripe idempotency key per request and a unique local payment row. Stripe and PostgreSQL cannot share a transaction; the Stripe key covers a retry if Stripe accepted a session but the database write did not commit.

A browser return from Stripe is only navigation. The webhook endpoint verifies Stripe's signature over the raw body, rejects live-mode events, and checks the event's session ID, request ID, amount, currency, and paid status against the stored payment. The unique Stripe event ID makes delivery replay safe. The local payment becomes `PAID` only in that transaction. A real deployment would also require a production secret store, TLS, stricter network exposure, account-specific money and tax policy, and operational alerting for failed webhooks.
