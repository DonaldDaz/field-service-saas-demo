import { useEffect, useState } from "react";
import { ActivityIndicator, Pressable, SafeAreaView, ScrollView, StatusBar, StyleSheet, Text, View } from "react-native";
import { makeRedirectUri, useAuthRequest, useAutoDiscovery } from "expo-auth-session";
import * as SecureStore from "expo-secure-store";
import * as WebBrowser from "expo-web-browser";

WebBrowser.maybeCompleteAuthSession();

const issuer = process.env.EXPO_PUBLIC_OIDC_ISSUER ?? "http://localhost:8080/realms/fieldflow";
const apiUrl = process.env.EXPO_PUBLIC_API_URL ?? "http://localhost:8081";
const redirectUri = makeRedirectUri({ native: "fieldflow://auth" });
const storageKey = "fieldflow-technician-token";

type TokenState = { accessToken: string; refreshToken?: string; expiresAt: number };
type Job = {
  id: string; problem: string; skill: string; status: string; customerUsername: string;
  windowStart: string; windowEnd: string; latitude: number; longitude: number;
};
type StatusEvent = { status: string; actorUsername: string; createdAt: string };

const nextStatus: Record<string, { status: string; label: string }> = {
  ASSIGNED: { status: "ACCEPTED", label: "Accept assignment" },
  ACCEPTED: { status: "EN_ROUTE", label: "Start travel" },
  EN_ROUTE: { status: "ON_SITE", label: "Arrive on site" },
  ON_SITE: { status: "COMPLETED", label: "Complete job" },
};

export default function App() {
  const discovery = useAutoDiscovery(issuer);
  const [request, response, promptAsync] = useAuthRequest({
    clientId: "field-technician",
    redirectUri,
    scopes: ["openid", "profile"],
    usePKCE: true,
  }, discovery);
  const [token, setToken] = useState<TokenState | null>(null);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [events, setEvents] = useState<StatusEvent[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function saveToken(value: TokenState) {
    await SecureStore.setItemAsync(storageKey, JSON.stringify(value));
    setToken(value);
  }

  useEffect(() => {
    if (!discovery?.tokenEndpoint) return;
    SecureStore.getItemAsync(storageKey).then(async (stored) => {
      if (!stored) return;
      const current = JSON.parse(stored) as TokenState;
      if (current.expiresAt > Date.now() + 30_000) {
        setToken(current);
        return;
      }
      if (!current.refreshToken) return;
      const body = new URLSearchParams({
        grant_type: "refresh_token", client_id: "field-technician", refresh_token: current.refreshToken,
      });
      const result = await fetch(discovery.tokenEndpoint!, { method: "POST", body, headers: { "Content-Type": "application/x-www-form-urlencoded" } });
      if (!result.ok) return;
      const fresh = await result.json();
      await saveToken({ accessToken: fresh.access_token, refreshToken: fresh.refresh_token ?? current.refreshToken,
        expiresAt: Date.now() + fresh.expires_in * 1000 });
    }).catch(() => setError("The saved session could not be restored."));
  }, [discovery?.tokenEndpoint]);

  useEffect(() => {
    if (response?.type !== "success" || !discovery?.tokenEndpoint || !request?.codeVerifier) return;
    const body = new URLSearchParams({
      grant_type: "authorization_code", client_id: "field-technician", code: response.params.code,
      redirect_uri: redirectUri, code_verifier: request.codeVerifier,
    });
    fetch(discovery.tokenEndpoint, { method: "POST", body, headers: { "Content-Type": "application/x-www-form-urlencoded" } })
      .then(async (result) => {
        if (!result.ok) throw new Error("Sign-in could not be completed.");
        const data = await result.json();
        await saveToken({ accessToken: data.access_token, refreshToken: data.refresh_token,
          expiresAt: Date.now() + data.expires_in * 1000 });
      }).catch((cause) => setError((cause as Error).message));
  }, [response, discovery?.tokenEndpoint, request?.codeVerifier]);

  async function api<T>(path: string, method = "GET", body?: object): Promise<T> {
    if (!token) throw new Error("Sign in first.");
    const result = await fetch(`${apiUrl}${path}`, {
      method,
      headers: { Authorization: `Bearer ${token.accessToken}`, "Content-Type": "application/json" },
      body: body ? JSON.stringify(body) : undefined,
    });
    if (!result.ok) {
      const details = await result.json().catch(() => ({}));
      throw new Error(details.message || `Request failed (${result.status})`);
    }
    return result.json() as Promise<T>;
  }

  async function load(currentId = selectedId) {
    setBusy(true);
    setError("");
    try {
      const list = await api<Job[]>("/api/requests");
      setJobs(list);
      const id = currentId ?? list[0]?.id;
      setSelectedId(id ?? null);
      setEvents(id ? await api<StatusEvent[]>(`/api/requests/${id}/history`) : []);
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy(false);
    }
  }

  useEffect(() => {
    if (token) load(null);
  }, [token?.accessToken]);

  async function advance(job: Job) {
    const action = nextStatus[job.status];
    if (!action) return;
    setBusy(true);
    setError("");
    try {
      await api(`/api/requests/${job.id}/status`, "PATCH", { status: action.status });
      await load(job.id);
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function signOut() {
    await SecureStore.deleteItemAsync(storageKey);
    setToken(null);
    setJobs([]);
    setEvents([]);
    setSelectedId(null);
  }

  const selected = jobs.find((job) => job.id === selectedId);
  return <SafeAreaView style={styles.safe}>
    <StatusBar barStyle="light-content" />
    <ScrollView contentContainerStyle={styles.page}>
      <View style={styles.header}><View style={styles.brand}><Text style={styles.brandText}>FF</Text></View><View><Text style={styles.brandName}>fieldflow.</Text><Text style={styles.brandSub}>TECHNICIAN WORKSPACE</Text></View></View>
      <View style={styles.hero}><Text style={styles.kicker}>FIELD SERVICE · MOBILE</Text><Text style={styles.title}>Your next job,{"\n"}<Text style={styles.titleAccent}>clearly mapped.</Text></Text><Text style={styles.heroCopy}>Accept an assignment, update its progress, and leave a shared timeline for the customer and operator.</Text></View>
      {error ? <Text style={styles.error}>{error}</Text> : null}
      {!token ? <View style={styles.panel}><Text style={styles.panelTitle}>Secure sign-in</Text><Text style={styles.muted}>Use the seeded technician account through Keycloak. Authorization uses code + PKCE.</Text><Pressable style={styles.primary} disabled={!request} onPress={() => promptAsync()}><Text style={styles.primaryText}>Sign in with FieldFlow  ↗</Text></Pressable></View> : <>
        <View style={styles.toolbar}><View><Text style={styles.sectionKicker}>ASSIGNMENTS</Text><Text style={styles.sectionTitle}>{jobs.length} visible jobs</Text></View><Pressable onPress={() => load()}><Text style={styles.refresh}>Refresh ↻</Text></Pressable></View>
        {busy && <ActivityIndicator color="#0c6d6b" />}
        {jobs.length === 0 && <Text style={styles.empty}>No work is assigned to this technician.</Text>}
        {jobs.map((job) => <Pressable key={job.id} style={[styles.jobRow, selectedId === job.id && styles.jobRowActive]} onPress={() => load(job.id)}><View style={styles.rowTop}><Text style={styles.jobProblem}>{job.problem}</Text><Text style={styles.badge}>{job.status.replaceAll("_", " ")}</Text></View><Text style={styles.jobMeta}>{job.skill.toUpperCase()} · {new Date(job.windowStart).toLocaleString()}</Text></Pressable>)}
        {selected && <View style={styles.panel}><Text style={styles.sectionKicker}>JOB {selected.id.slice(0, 8).toUpperCase()}</Text><Text style={styles.panelTitle}>{selected.problem}</Text><View style={styles.fact}><Text style={styles.factLabel}>CUSTOMER</Text><Text>{selected.customerUsername}</Text></View><View style={styles.fact}><Text style={styles.factLabel}>WINDOW</Text><Text>{new Date(selected.windowStart).toLocaleString()}</Text></View><View style={styles.fact}><Text style={styles.factLabel}>LOCATION</Text><Text>{selected.latitude.toFixed(4)}, {selected.longitude.toFixed(4)}</Text></View>{nextStatus[selected.status] && <Pressable style={styles.primary} disabled={busy} onPress={() => advance(selected)}><Text style={styles.primaryText}>{nextStatus[selected.status].label}  ↗</Text></Pressable>}<Text style={styles.historyTitle}>STATUS HISTORY</Text>{events.map((event, index) => <View style={styles.event} key={`${event.status}-${index}`}><View style={styles.dot} /><View><Text style={styles.eventTitle}>{event.status.replaceAll("_", " ")}</Text><Text style={styles.eventMeta}>{event.actorUsername} · {new Date(event.createdAt).toLocaleString()}</Text></View></View>)}</View>}
        <Pressable onPress={signOut}><Text style={styles.signOut}>Sign out</Text></Pressable>
      </>}
      <Text style={styles.footer}>FIELDFLOW · SYNTHETIC OPERATIONS</Text>
    </ScrollView>
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: "#f3f6f6" }, page: { padding: 18, paddingBottom: 48 },
  header: { flexDirection: "row", alignItems: "center", gap: 11, marginBottom: 18 },
  brand: { backgroundColor: "#123a49", width: 42, height: 42, borderRadius: 10, alignItems: "center", justifyContent: "center" },
  brandText: { color: "#ec9d7b", fontSize: 17, fontWeight: "900" }, brandName: { fontSize: 23, fontWeight: "900", letterSpacing: -1, color: "#173443" }, brandSub: { color: "#769099", letterSpacing: 2, fontSize: 8, fontWeight: "700" },
  hero: { backgroundColor: "#103d4a", borderRadius: 16, padding: 27, marginBottom: 22 }, kicker: { color: "#a3d6d2", letterSpacing: 2, fontSize: 10, fontWeight: "800" }, title: { color: "white", fontSize: 34, fontWeight: "800", letterSpacing: -1.5, marginTop: 20 }, titleAccent: { color: "#c8e6dc", fontWeight: "400" }, heroCopy: { color: "#c1d3d3", fontSize: 13, lineHeight: 20, marginTop: 14 },
  panel: { backgroundColor: "white", borderColor: "#dce4e8", borderWidth: 1, borderRadius: 12, padding: 22, marginBottom: 20 }, panelTitle: { color: "#173443", fontSize: 22, fontWeight: "800", marginBottom: 12 }, muted: { color: "#71858d", fontSize: 13, lineHeight: 19, marginBottom: 20 }, primary: { backgroundColor: "#ef734b", borderRadius: 7, padding: 15, marginTop: 20, alignItems: "center" }, primaryText: { color: "white", fontSize: 13, fontWeight: "800" }, error: { backgroundColor: "#fff0ec", color: "#9f3828", padding: 14, borderRadius: 6, marginBottom: 15 },
  toolbar: { flexDirection: "row", justifyContent: "space-between", alignItems: "center", marginBottom: 13 }, sectionKicker: { fontSize: 10, color: "#0c6d6b", fontWeight: "800", letterSpacing: 2 }, sectionTitle: { fontSize: 22, fontWeight: "800", color: "#173443", marginTop: 5 }, refresh: { color: "#0c6d6b", fontWeight: "800" }, empty: { padding: 24, color: "#71858d" },
  jobRow: { backgroundColor: "white", borderWidth: 1, borderColor: "#dce4e8", borderRadius: 10, padding: 17, marginBottom: 10 }, jobRowActive: { borderColor: "#0c6d6b", backgroundColor: "#f0f9f6" }, rowTop: { flexDirection: "row", justifyContent: "space-between", gap: 10 }, jobProblem: { flex: 1, color: "#173443", fontWeight: "700", fontSize: 14 }, badge: { color: "#0c6d6b", backgroundColor: "#ddf0e9", fontSize: 9, fontWeight: "800", padding: 5, borderRadius: 3, overflow: "hidden" }, jobMeta: { color: "#78909a", fontSize: 10, marginTop: 9 },
  fact: { borderTopWidth: 1, borderColor: "#e6ecee", paddingVertical: 12, gap: 5 }, factLabel: { color: "#80929a", fontSize: 9, fontWeight: "800", letterSpacing: 1.5 }, historyTitle: { fontSize: 10, fontWeight: "800", color: "#70838c", letterSpacing: 1.5, marginTop: 25, marginBottom: 15 }, event: { flexDirection: "row", gap: 12, paddingBottom: 18 }, dot: { width: 9, height: 9, borderRadius: 5, borderColor: "#0c6d6b", borderWidth: 2, marginTop: 3 }, eventTitle: { color: "#173443", fontWeight: "700", fontSize: 12 }, eventMeta: { color: "#81939b", fontSize: 10, marginTop: 3 }, signOut: { color: "#0c6d6b", textAlign: "center", fontWeight: "800", padding: 17 }, footer: { color: "#9babb0", fontSize: 9, letterSpacing: 1.5, textAlign: "center", marginTop: 8 },
});
