"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { UserManager, type User } from "oidc-client-ts";

type Actor = { username: string; role: "customer" | "operator" | "technician" };
type ServiceRequest = {
  id: string; customerUsername: string; problem: string; skill: string;
  latitude: number; longitude: number; windowStart: string; windowEnd: string;
  status: string; technicianUsername: string | null; paymentStatus: string; createdAt: string;
};
type Candidate = { username: string; displayName: string; skill: string; distanceKm: number };
type StatusEvent = { status: string; actorUsername: string; createdAt: string };

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8081";
const authority = process.env.NEXT_PUBLIC_OIDC_AUTHORITY ?? "http://localhost:8080/realms/fieldflow";

async function api<T>(token: string, path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${apiUrl}${path}`, {
    ...init,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(init.body instanceof FormData ? {} : { "Content-Type": "application/json" }),
      ...init.headers,
    },
    cache: "no-store",
  });
  if (!response.ok) {
    const details = await response.json().catch(() => ({}));
    throw new Error(details.message || details.error || `Request failed (${response.status})`);
  }
  return response.json() as Promise<T>;
}

function localDateTime(date: Date) {
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
}

function nextWindow() {
  const start = new Date(Date.now() + 24 * 60 * 60 * 1000);
  start.setHours(9, 0, 0, 0);
  return localDateTime(start);
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat("en", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}

export default function Home() {
  const manager = useMemo(() => typeof window === "undefined" ? null : new UserManager({
    authority,
    client_id: "field-web",
    redirect_uri: `${window.location.origin}/`,
    post_logout_redirect_uri: `${window.location.origin}/`,
    response_type: "code",
    scope: "openid profile",
  }), []);
  const started = useRef(false);
  const [user, setUser] = useState<User | null>(null);
  const [actor, setActor] = useState<Actor | null>(null);
  const [requests, setRequests] = useState<ServiceRequest[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [history, setHistory] = useState<StatusEvent[]>([]);
  const [candidates, setCandidates] = useState<Candidate[]>([]);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState("");
  const [skill, setSkill] = useState("plumbing");
  const [latitude, setLatitude] = useState("45.4650");
  const [longitude, setLongitude] = useState("9.1950");
  const [windowStart, setWindowStart] = useState(nextWindow);
  const [windowEnd, setWindowEnd] = useState(() => {
    const end = new Date(nextWindow());
    end.setHours(end.getHours() + 2);
    return localDateTime(end);
  });
  const submissionKey = useRef<string>(crypto.randomUUID());

  const refresh = useCallback(async (currentUser: User, currentActor: Actor, currentId?: string | null) => {
    const list = await api<ServiceRequest[]>(currentUser.access_token, "/api/requests");
    setRequests(list);
    const id = currentId ?? list[0]?.id;
    if (id) {
      setSelectedId(id);
      const [events, eligible] = await Promise.all([
        api<StatusEvent[]>(currentUser.access_token, `/api/requests/${id}/history`),
        currentActor.role === "operator"
          ? api<Candidate[]>(currentUser.access_token, `/api/requests/${id}/candidates`)
          : Promise.resolve([]),
      ]);
      setHistory(events);
      setCandidates(eligible);
    } else {
      setSelectedId(null);
      setHistory([]);
      setCandidates([]);
    }
  }, []);

  useEffect(() => {
    if (!manager || started.current) return;
    started.current = true;
    (async () => {
      try {
        let current: User | null;
        if (new URLSearchParams(window.location.search).has("code")) {
          current = await manager.signinRedirectCallback();
          window.history.replaceState({}, "", window.location.pathname);
        } else {
          current = await manager.getUser();
        }
        if (!current || current.expired) return;
        const identity = await api<Actor>(current.access_token, "/api/me");
        setUser(current);
        setActor(identity);
        await refresh(current, identity);
      } catch (cause) {
        setError((cause as Error).message);
      }
    })();
  }, [manager, refresh]);

  useEffect(() => {
    if (!user || !actor) return;
    const timer = window.setInterval(() => {
      refresh(user, actor, selectedId).catch(() => undefined);
    }, 5000);
    return () => window.clearInterval(timer);
  }, [user, actor, selectedId, refresh]);

  const selected = requests.find((request) => request.id === selectedId) ?? null;

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError("");
    try { await action(); } catch (cause) { setError((cause as Error).message); } finally { setBusy(false); }
  }

  function createRequest(event: FormEvent) {
    event.preventDefault();
    if (!user || !actor) return;
    run(async () => {
      const created = await api<ServiceRequest>(user.access_token, "/api/requests", {
        method: "POST",
        headers: { "Idempotency-Key": submissionKey.current },
        body: JSON.stringify({
          problem, skill, latitude: Number(latitude), longitude: Number(longitude),
          windowStart: new Date(windowStart).toISOString(), windowEnd: new Date(windowEnd).toISOString(),
        }),
      });
      submissionKey.current = crypto.randomUUID();
      setProblem("");
      await refresh(user, actor, created.id);
    });
  }

  function assign(username: string) {
    if (!user || !actor || !selected) return;
    run(async () => {
      await api(user.access_token, `/api/requests/${selected.id}/assign`, {
        method: "POST", body: JSON.stringify({ technicianUsername: username }),
      });
      await refresh(user, actor, selected.id);
    });
  }

  function checkout() {
    if (!user || !selected) return;
    run(async () => {
      const result = await api<{ url: string }>(user.access_token, `/api/requests/${selected.id}/checkout`, { method: "POST" });
      window.location.assign(result.url);
    });
  }

  function openRequest(id: string) {
    if (!user || !actor) return;
    run(() => refresh(user, actor, id));
  }

  return <div className="shell">
    <aside className="rail">
      <div className="brand-mark">F<span>F</span></div>
      <p className="rail-label">FIELD OPERATIONS</p>
      <a href="#workspace" className="rail-link active">▦ <span>Workspace</span></a>
      <a href="#requests" className="rail-link">▤ <span>Requests</span></a>
      <a href="#workflow" className="rail-link">◇ <span>Workflow</span></a>
      <div className="rail-spacer" />
      <div className="rail-foot">SYNTHETIC DATA<br />LOCAL ENVIRONMENT</div>
    </aside>

    <main id="workspace" className="main">
      <header className="topbar">
        <div className="wordmark">fieldflow<span>.</span><small>Service operations</small></div>
        <div className="top-actions">
          <span className="live"><i /> SYSTEM ONLINE</span>
          {actor && <span className="account">{actor.username} <b>{actor.role}</b></span>}
          {user && manager && <button className="text-button" onClick={() => manager.signoutRedirect()}>Sign out ↗</button>}
        </div>
      </header>

      <div className="content">
        <div className="hero">
          <div>
            <p className="eyebrow">ONE WORKSPACE · END-TO-END SERVICE</p>
            <h1>Every job,<br /><em>in clear view.</em></h1>
            <p className="intro">From a customer request to the final payment event, everyone sees the same verified timeline.</p>
            {!user && <button className="primary" onClick={() => manager?.signinRedirect()}>Sign in with FieldFlow <span>↗</span></button>}
          </div>
          <div className="hero-art" aria-hidden="true">
            <div className="art-ring ring-one" /><div className="art-ring ring-two" />
            <span className="art-node top">01 · REQUEST</span><span className="art-node mid">02 · ASSIGN</span>
            <span className="art-node bottom">03 · COMPLETE</span>
            <div className="art-center">FF</div>
          </div>
        </div>

        <div className="metrics">
          <div><span>ACTIVE REQUESTS</span><strong>{requests.filter((item) => item.status !== "COMPLETED").length}</strong><small>Visible to your role</small></div>
          <div><span>COMPLETED</span><strong>{requests.filter((item) => item.status === "COMPLETED").length}</strong><small>Work closed</small></div>
          <div><span>PAYMENTS VERIFIED</span><strong>{requests.filter((item) => item.paymentStatus === "PAID").length}</strong><small>Webhook confirmed</small></div>
        </div>

        {error && <div role="alert" className="alert">{error}</div>}
        {!actor && <div className="sign-in-note">Sign in as a customer or operator to manage requests. Technician assignments are handled in the mobile app.</div>}

        {actor?.role === "customer" && <section className="create-section">
          <div className="section-heading"><div><p className="eyebrow">NEW SERVICE REQUEST</p><h2>Tell us what needs attention.</h2></div><span>01 / CUSTOMER</span></div>
          <form className="request-form card" onSubmit={createRequest}>
            <label className="wide">What is the problem?<textarea required minLength={10} maxLength={500} value={problem} onChange={(event) => setProblem(event.target.value)} placeholder="Describe the issue clearly, e.g. the kitchen sink is leaking under the cabinet." /></label>
            <label>Required skill<select value={skill} onChange={(event) => setSkill(event.target.value)}><option value="plumbing">Plumbing</option><option value="electrical">Electrical</option></select></label>
            <div className="form-hint">Location uses WGS84 latitude and longitude. Milan coordinates are prefilled for the local dataset.</div>
            <label>Latitude<input type="number" step="any" min="-90" max="90" required value={latitude} onChange={(event) => setLatitude(event.target.value)} /></label>
            <label>Longitude<input type="number" step="any" min="-180" max="180" required value={longitude} onChange={(event) => setLongitude(event.target.value)} /></label>
            <label>Window start<input type="datetime-local" required value={windowStart} onChange={(event) => setWindowStart(event.target.value)} /></label>
            <label>Window end<input type="datetime-local" required value={windowEnd} onChange={(event) => setWindowEnd(event.target.value)} /></label>
            <div className="form-footer"><span>One request per submission key, even after a retry.</span><button disabled={busy} className="primary">Create request <span>↗</span></button></div>
          </form>
        </section>}

        {actor && <section id="requests" className="requests-section">
          <div className="section-heading"><div><p className="eyebrow">SHARED RECORD</p><h2>{actor.role === "operator" ? "Dispatch board" : actor.role === "customer" ? "Your requests" : "Assigned work"}</h2></div><button className="outline" onClick={() => run(() => refresh(user!, actor, selectedId))}>Refresh ↻</button></div>
          <div className="workspace-grid">
            <div className="request-list card">
              {requests.length === 0 && <p className="empty">No requests are visible yet.</p>}
              {requests.map((item) => <button key={item.id} className={`request-row ${selectedId === item.id ? "selected" : ""}`} onClick={() => openRequest(item.id)}>
                <span className="row-top"><b>{item.problem}</b><em className={`status ${item.status.toLowerCase()}`}>{item.status.replaceAll("_", " ")}</em></span>
                <span className="row-sub">{item.skill.toUpperCase()} · {formatDate(item.windowStart)}</span>
                <span className="row-meta">{item.technicianUsername ? `Technician ${item.technicianUsername}` : "Awaiting assignment"}<span>↗</span></span>
              </button>)}
            </div>
            <div className="detail card">
              {!selected ? <div className="empty">Select a request to view its details.</div> : <>
                <div className="detail-top"><span className="eyebrow">REQUEST {selected.id.slice(0, 8).toUpperCase()}</span><em className={`status ${selected.status.toLowerCase()}`}>{selected.status.replaceAll("_", " ")}</em></div>
                <h3>{selected.problem}</h3>
                <div className="detail-facts"><div><span>SKILL</span><b>{selected.skill}</b></div><div><span>TIME WINDOW</span><b>{formatDate(selected.windowStart)} – {formatDate(selected.windowEnd)}</b></div><div><span>LOCATION</span><b>{selected.latitude.toFixed(4)}, {selected.longitude.toFixed(4)}</b></div><div><span>TECHNICIAN</span><b>{selected.technicianUsername ?? "Unassigned"}</b></div></div>
                {actor.role === "operator" && selected.status === "REQUESTED" && <div className="candidates"><h4>Eligible technicians <span>{candidates.length}</span></h4><p>Matched on skill, availability, and PostGIS distance.</p>{candidates.length === 0 && <small>No technician is available for this window and location.</small>}{candidates.map((person) => <div className="candidate" key={person.username}><div className="avatar">{person.displayName.split(" ").map((word) => word[0]).join("")}</div><div><b>{person.displayName}</b><small>{person.skill} · {person.distanceKm} km away</small></div><button disabled={busy} onClick={() => assign(person.username)}>Assign ↗</button></div>)}</div>}
                {actor.role === "customer" && selected.status === "COMPLETED" && <div className="payment"><div><b>Test payment</b><span>€49.00 · {selected.paymentStatus}</span></div>{selected.paymentStatus !== "PAID" && <button disabled={busy} onClick={checkout}>Open Stripe Checkout ↗</button>}</div>}
                <div className="timeline"><h4>Status history</h4>{history.map((event, index) => <div className="event" key={`${event.status}-${index}`}><div className="event-dot" /><div><b>{event.status.replaceAll("_", " ")}</b><span>by {event.actorUsername} · {formatDate(event.createdAt)}</span></div></div>)}</div>
              </>}
            </div>
          </div>
        </section>}

        <section id="workflow" className="workflow"><div className="section-heading"><div><p className="eyebrow">OPERATING MODEL</p><h2>One record. Three roles.</h2></div><span>FIELD SERVICE FLOW</span></div><div className="steps"><div><span>01</span><h3>Customer</h3><p>Submits a problem, location, and preferred window. Follows the history and pays only after completion.</p></div><div><span>02</span><h3>Operator</h3><p>Reviews eligible technicians and assigns one. Skill, availability, and distance are evaluated by the API.</p></div><div><span>03</span><h3>Technician</h3><p>Accepts, travels, arrives, and completes the job from the React Native app.</p></div><div><span>04</span><h3>Verification</h3><p>A signed Stripe webhook changes payment status. A browser redirect alone never marks a job paid.</p></div></div></section>
        <footer><span>FIELDFLOW · SYNTHETIC SERVICE OPERATIONS</span><span>Next.js · Spring Boot · PostGIS · Keycloak · Stripe</span></footer>
      </div>
    </main>
  </div>;
}
