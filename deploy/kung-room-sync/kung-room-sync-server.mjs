#!/usr/bin/env node
import http from "node:http";
import fs from "node:fs/promises";
import path from "node:path";
import { randomBytes } from "node:crypto";

const port = Number(process.env.PORT || process.env.KUNG_ROOM_SYNC_PORT || 8765);
// All interfaces for the LAN; 127.0.0.1 once a Cloudflare Tunnel is the only way in.
const host = process.env.KUNG_ROOM_SYNC_HOST || "0.0.0.0";
const dataFile = path.resolve(process.env.KUNG_ROOM_DATA_PATH || "room-sync-data/known-rooms.json");
const maxBodyBytes = 512 * 1024;
const liveRunTtlMillis = Number(process.env.KUNG_LIVE_ROOM_SYNC_TTL_MS || 180_000);
const liveRuns = new Map();
const hypixelKey = process.env.HYPIXEL_API_KEY || "";
const hypixelApiUrl = process.env.KUNG_HYPIXEL_API_URL || "https://api.hypixel.net";
const profileCacheMillis = Number(process.env.KUNG_PROFILE_CACHE_MS || 120_000);
// uuid -> { at, result: Promise<{status, body}> }; one Hypixel request per player per cache window.
const profileCache = new Map();

// Sign-in is the vanilla server-join handshake: we hand out a challenge, the client joins it at
// Mojang with its own access token, and hasJoined tells us who did. No shared secret in the jar.
const mojangSessionUrl = process.env.KUNG_MOJANG_SESSION_URL || "https://sessionserver.mojang.com";
// Any Mojang-verified account may sign in. Only room uploads (POST /rooms/report, a file everyone
// downloads) need the writers list: KUNG_ROOM_WRITER_UUIDS, else the older KUNG_ALLOWED_UUIDS.
// Empty means nobody uploads.
const roomWriters = new Set(String(process.env.KUNG_ROOM_WRITER_UUIDS ?? process.env.KUNG_ALLOWED_UUIDS ?? "").split(",")
  .map((uuid) => uuid.trim().replace(/-/g, "").toLowerCase())
  .filter(Boolean));
const challengeTtlMillis = Number(process.env.KUNG_CHALLENGE_TTL_MS || 60_000);
const sessionTtlMillis = Number(process.env.KUNG_SESSION_TTL_MS || 43_200_000);
// challenge -> { expiresAt }, token -> { uuid, name, expiresAt }; memory only, a restart signs everyone out.
const challenges = new Map();
const sessions = new Map();

// Hard caps: a full map answers 503 instead of letting junk grow the heap.
const maxLiveRuns = 1000;
const maxChallenges = 10_000;
const maxSessions = 10_000;
const maxTrackedIps = 10_000;

const server = http.createServer(async (request, response) => {
  try {
    if (request.method === "GET" && request.url === "/health") {
      return json(response, 200, { ok: true });
    }
    if (request.method === "POST" && (request.url === "/auth/challenge" || request.url === "/auth/login")) {
      if (!withinLimit(authRequests, authLimit, request)) {
        return json(response, 429, { error: "too many requests" });
      }
      if (request.url === "/auth/challenge") {
        return json(response, 200, issueChallenge());
      }
      const [status, result] = await login(await readJson(request));
      return json(response, status, result);
    }
    const session = sessionFor(request);
    if (!session) {
      return json(response, 401, { error: "unauthorized" });
    }
    if (request.method === "GET" && request.url === "/rooms") {
      return json(response, 200, await readDatabase());
    }
    if (request.method === "POST" && request.url === "/rooms/report") {
      if (!roomWriters.has(session.uuid)) {
        return json(response, 403, { error: "not allowed to upload" });
      }
      if (!withinLimit(roomReportRequests, roomReportLimit, request, session.uuid)) {
        return json(response, 429, { error: "too many requests" });
      }
      const report = await readJson(request);
      const result = await mergeRoomReport(report);
      return json(response, 200, result);
    }
    if (request.method === "POST" && request.url === "/runs/live/report") {
      // The session says who reports; report.player is ignored so nobody can post as someone else.
      if (!withinLimit(liveReportRequests, liveReportLimit, request, session.uuid)) {
        return json(response, 429, { error: "too many requests" });
      }
      const report = await readJson(request);
      const result = mergeLiveRunReport(report, session.name);
      return json(response, 200, result);
    }
    if (request.method === "GET" && request.url.startsWith("/runs/live?")) {
      const url = new URL(request.url, "http://localhost");
      return json(response, 200, liveRunSnapshot(String(url.searchParams.get("runKey") || ""), session.name));
    }
    if (request.method === "GET" && request.url.startsWith("/hypixel/profiles?")) {
      const uuid = String(new URL(request.url, "http://localhost").searchParams.get("uuid") || "")
        .replace(/-/g, "").toLowerCase();
      const validUuid = /^[0-9a-f]{32}$/.test(uuid);
      // One line per lookup for journalctl -f; only a checked uuid, never a token, key or body.
      let outcome = "error";
      try {
        if (!withinLimit(profileRequests, profileLimit, request)
          || !withinLimit(profileUserRequests, profileUserLimit, request, session.uuid)) {
          outcome = "rate limited 429";
          return json(response, 429, { error: "too many requests" });
        }
        if (!validUuid) {
          outcome = "bad uuid 400";
          return json(response, 400, { error: "uuid must be 32 hex characters" });
        }
        if (!hypixelKey) {
          outcome = "no key 503";
          return json(response, 503, { error: "HYPIXEL_API_KEY is not set on the server" });
        }
        const result = await cachedProfiles(uuid);
        outcome = `${result.hit ? "cache hit" : "hypixel"} ${result.status}`;
        response.writeHead(result.status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
        return response.end(result.body);
      } finally {
        console.log(`profiles ${validUuid ? uuid : "?"} for ${session.name}: ${outcome}`);
      }
    }
    return json(response, 404, { error: "not found" });
  } catch (error) {
    // Client errors carry a status and are not logged; nothing here may log a body, token or challenge.
    if (!error.status) {
      console.error(error);
    }
    return json(response, error.status || 500, { error: error.message || "server error" });
  }
});

server.listen(port, host, () => {
  console.log(`Kung room sync server listening on ${host}:${server.address().port}`);
  console.log(`Data file: ${dataFile}`);
  console.log(`Auth: Mojang sign-in, any verified player; room uploads: ${roomWriters.size} writer(s)`);
  console.log(hypixelKey ? `Hypixel proxy: on, cache ${profileCacheMillis} ms` : "Hypixel proxy: off (no HYPIXEL_API_KEY)");
});

// Per client IP, so one person cannot burn the shared Hypixel key or spam Mojang. Behind Cloudflare
// Tunnel every request comes from cloudflared, the real address is in CF-Connecting-IP.
// The per-user limits are keyed on the session's UUID instead. The client posts a live report at most
// every 10 ticks (2/s, 600 per 5 min), so its default is 3x that; a room report is a manual learn.
const profileLimit = Number(process.env.KUNG_PROFILE_LIMIT_PER_5_MIN || 30);
const profileRequests = new Map();
const profileUserLimit = Number(process.env.KUNG_PROFILE_USER_LIMIT_PER_5_MIN || 30);
const profileUserRequests = new Map();
const liveReportLimit = Number(process.env.KUNG_LIVE_REPORT_LIMIT_PER_5_MIN || 1800);
const liveReportRequests = new Map();
const roomReportLimit = Number(process.env.KUNG_ROOM_REPORT_LIMIT_PER_5_MIN || 30);
const roomReportRequests = new Map();
const authLimit = Number(process.env.KUNG_AUTH_LIMIT_PER_5_MIN || 20);
const authRequests = new Map();

// Fixed 5 minute window per key; the key defaults to the client IP.
function withinLimit(requests, limit, request,
  key = String(request.headers["cf-connecting-ip"] || request.socket.remoteAddress || "?")) {
  const now = Date.now();
  for (const [tracked, window] of requests) {
    if (now - window.start > 300_000) {
      requests.delete(tracked);
    }
  }
  if (!requests.has(key)) {
    roomFor(requests, maxTrackedIps);
  }
  const window = requests.get(key) || { start: now, count: 0 };
  window.count += 1;
  requests.set(key, window);
  return window.count <= limit;
}

function issueChallenge() {
  pruneExpired(challenges);
  roomFor(challenges, maxChallenges);
  const challenge = randomBytes(16).toString("hex");
  challenges.set(challenge, { expiresAt: Date.now() + challengeTtlMillis });
  return { challenge, expiresInMs: challengeTtlMillis };
}

async function login(body) {
  const challenge = String(body?.challenge || "");
  const name = String(body?.name || "");
  const entry = challenges.get(challenge);
  // Single use even when this attempt fails, so a leaked challenge is worth one try at most.
  challenges.delete(challenge);
  if (!entry || entry.expiresAt <= Date.now() || !/^[A-Za-z0-9_]{1,16}$/.test(name)) {
    return [401, { error: "not verified" }];
  }
  let profile;
  try {
    const verified = await fetch(`${mojangSessionUrl}/session/minecraft/hasJoined?username=${encodeURIComponent(name)}`
      + `&serverId=${encodeURIComponent(challenge)}`, { signal: AbortSignal.timeout(10_000) });
    if (verified.status !== 200) {
      return [401, { error: "not verified" }];
    }
    profile = await verified.json();
  } catch {
    return [502, { error: "Mojang session server unreachable" }];
  }
  const uuid = String(profile?.id || "").replace(/-/g, "").toLowerCase();
  if (!/^[0-9a-f]{32}$/.test(uuid)) {
    return [401, { error: "not verified" }];
  }
  pruneExpired(sessions);
  // One session per player: signing in again replaces the old token, so repeated logins cannot fill the table.
  // ponytail: O(n) over at most maxSessions per login; a uuid -> token map if logins ever get hot.
  for (const [old, entry] of sessions) {
    if (entry.uuid === uuid) {
      sessions.delete(old);
    }
  }
  roomFor(sessions, maxSessions);
  const token = randomBytes(32).toString("hex");
  const session = { uuid, name: String(profile.name || name), expiresAt: Date.now() + sessionTtlMillis };
  sessions.set(token, session);
  console.log(`Signed in ${session.name} (${uuid})`);
  return [200, { token, expiresAt: session.expiresAt, uuid, name: session.name }];
}

function sessionFor(request) {
  const token = /^Bearer ([0-9a-f]{64})$/.exec(request.headers.authorization || "")?.[1];
  const session = token && sessions.get(token);
  return session && session.expiresAt > Date.now() ? session : null;
}

function pruneExpired(map) {
  const now = Date.now();
  for (const [key, entry] of map) {
    if (entry.expiresAt <= now) {
      map.delete(key);
    }
  }
}

function roomFor(map, max) {
  if (map.size >= max) {
    throw httpError(503, "server busy");
  }
}

function httpError(status, message) {
  return Object.assign(new Error(message), { status });
}

function cachedProfiles(uuid) {
  const now = Date.now();
  for (const [key, entry] of profileCache) {
    if (now - entry.at > profileCacheMillis) {
      profileCache.delete(key);
    }
  }
  let entry = profileCache.get(uuid);
  const hit = Boolean(entry);
  if (!entry) {
    entry = { at: now, result: fetchProfiles(uuid) };
    profileCache.set(uuid, entry);
    // Only successes are cached; a 429 or network error must not stick for the whole window.
    entry.result.then(
      (result) => result.status === 200 || profileCache.delete(uuid),
      () => profileCache.delete(uuid),
    );
  }
  return entry.result.then((result) => ({ ...result, hit }));
}

async function fetchProfiles(uuid) {
  const upstream = await fetch(`${hypixelApiUrl}/v2/skyblock/profiles?uuid=${uuid}`, {
    headers: { "API-Key": hypixelKey, "User-Agent": "Kung-Server" },
    signal: AbortSignal.timeout(10_000),
  });
  const body = await upstream.text();
  if (upstream.status !== 200) {
    return { status: upstream.status, body };
  }
  return { status: 200, body: `${JSON.stringify(stripProfiles(JSON.parse(body), uuid))}\n` };
}

// Exactly what HypixelSkyBlockProfileClient.parseProfiles reads, and only the asking player's member:
// far less traffic, and coop members' data never leaves the server. true copies the value as is;
// an object keeps those keys, and only when the value is an object (the Java skips non-objects too).
const floorFields = { best_score: true, milestone_completions: true, fastest_time_s_plus: true, fastest_time_s: true };
const memberFields = {
  dungeons: {
    dungeon_types: { catacombs: { experience: true, ...floorFields }, master_catacombs: floorFields },
    player_classes: Object.fromEntries(["healer", "mage", "berserk", "archer", "tank"]
      .map((id) => [id, { experience: true }])),
    selected_dungeon_class: true,
    daily_runs: { current_day_stamp: true, completed_runs_count: true },
    dungeon_journal: { unlocked_journals: true },
    secrets: true,
  },
  player_data: {
    perks: { toxophilite: true, unbridled_rage: true, heart_of_gold: true, cold_efficiency: true, diamond_in_the_rough: true },
  },
  attributes: { stacks: { catacombs_explorer: true } },
};

function stripProfiles(root, uuid) {
  const profileFields = { profile_id: true, cute_name: true, selected: true, members: { [uuid]: memberFields } };
  return {
    success: root?.success,
    // null, missing and non-arrays all mean "no profiles" to the Java; null elements are skipped there.
    profiles: Array.isArray(root?.profiles)
      ? root.profiles.map((profile) => isObject(profile) ? pick(profile, profileFields) : null)
      : null,
  };
}

function pick(value, fields) {
  const result = {};
  for (const [key, field] of Object.entries(fields)) {
    if (!Object.hasOwn(value, key)) {
      continue;
    }
    if (field === true) {
      result[key] = value[key];
    } else if (isObject(value[key])) {
      result[key] = pick(value[key], field);
    }
  }
  return result;
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function json(response, status, value) {
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
  });
  response.end(`${JSON.stringify(value, null, 2)}\n`);
}

async function readBody(request) {
  const chunks = [];
  let bytes = 0;
  for await (const chunk of request) {
    bytes += chunk.length;
    if (bytes > maxBodyBytes) {
      throw httpError(413, "request body too large");
    }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString("utf8");
}

async function readJson(request) {
  const text = await readBody(request);
  try {
    return JSON.parse(text);
  } catch {
    // Not the parser's own message: it quotes the body, and a login body carries a challenge.
    throw httpError(400, "invalid JSON");
  }
}

async function readDatabase() {
  try {
    const text = await fs.readFile(dataFile, "utf8");
    const database = JSON.parse(text.replace(/^\uFEFF/, ""));
    if (!database || !Array.isArray(database.rooms)) {
      throw new Error("known-rooms.json must contain a rooms array");
    }
    return database;
  } catch (error) {
    if (error.code === "ENOENT") {
      return { schema: 1, rooms: [] };
    }
    throw error;
  }
}

async function writeDatabase(database) {
  await fs.mkdir(path.dirname(dataFile), { recursive: true });
  await fs.writeFile(dataFile, `${JSON.stringify(database, null, 2)}\n`, "utf8");
}

async function mergeRoomReport(report) {
  validateReport(report);
  const database = await readDatabase();
  database.schema = 1;
  database.rooms ||= [];

  const room = findOrCreateRoom(database, report);
  room.crypts = Math.max(Number(room.crypts || 0), Number(report.crypts || 0));

  const incoming = variantFromReport(report);
  const existing = room.variants.find((variant) => sameShape(variant, incoming));
  if (existing) {
    mergeVariant(existing, incoming);
  } else {
    room.variants.push(incoming);
  }

  sortDatabase(database);
  await writeDatabase(database);
  return {
    ok: true,
    name: report.name,
    type: report.type,
    secrets: report.secrets,
    cells: incoming.components.length,
  };
}

function mergeLiveRunReport(report, playerName) {
  validateLiveRunReport(report);
  pruneLiveRuns();

  const runKey = sourceKey(report.runKey, 120);
  const player = sourceKey(playerName, 32);
  let run = liveRuns.get(runKey);
  if (!run) {
    roomFor(liveRuns, maxLiveRuns);
    run = { runKey, updatedAt: 0, clients: new Map() };
    liveRuns.set(runKey, run);
  }

  const now = Date.now();
  const rooms = report.rooms
    ? report.rooms
    .map(liveRoomFromReport)
    .filter(Boolean)
    .slice(0, 36)
    : [];
  const doors = Array.isArray(report.doors)
    ? report.doors
      .map(liveDoorFromReport)
      .filter(Boolean)
      .slice(0, 60)
    : [];
  const players = Array.isArray(report.players)
    ? report.players
      .map(livePlayerFromReport)
      .filter(Boolean)
      .slice(0, 5)
    : [];
  run.clients.set(player.toLowerCase(), {
    player,
    updatedAt: Number(report.createdAt || now),
    rooms,
    doors,
    players,
  });
  run.updatedAt = now;
  return { ok: true, runKey, player, rooms: rooms.length, doors: doors.length, players: players.length };
}

function liveRunSnapshot(runKeyValue, requesterName) {
  pruneLiveRuns();
  const runKey = sourceKey(runKeyValue, 120);
  const requester = sourceKey(requesterName, 32).toLowerCase();
  const run = liveRuns.get(runKey);
  if (!run) {
    return { schema: 1, runKey, clients: [] };
  }
  return {
    schema: 1,
    runKey,
    updatedAt: run.updatedAt,
    clients: [...run.clients.values()]
      .filter(client => client.player.toLowerCase() !== requester)
      .map(client => ({
        player: client.player,
        updatedAt: client.updatedAt,
        rooms: client.rooms,
        doors: client.doors || [],
        players: client.players || [],
      })),
  };
}

function validateLiveRunReport(report) {
  if (!report || report.kind !== "kung-live-room-sync") {
    throw new Error("expected kind=kung-live-room-sync");
  }
  if (!report.runKey || typeof report.runKey !== "string") {
    throw new Error("live report needs a runKey");
  }
  if (report.rooms !== undefined && !Array.isArray(report.rooms)) {
    throw new Error("live report rooms must be an array");
  }
  if (report.doors !== undefined && !Array.isArray(report.doors)) {
    throw new Error("live report doors must be an array");
  }
  if (report.players !== undefined && !Array.isArray(report.players)) {
    throw new Error("live report players must be an array");
  }
  if (!Array.isArray(report.rooms) && !Array.isArray(report.doors) && !Array.isArray(report.players)) {
    throw new Error("live report needs rooms, doors, or players");
  }
}

function liveRoomFromReport(room) {
  if (!room || !Number.isInteger(Number(room.roomGridX)) || !Number.isInteger(Number(room.roomGridZ))) {
    return null;
  }
  const roomGridX = Number(room.roomGridX);
  const roomGridZ = Number(room.roomGridZ);
  if (roomGridX < 0 || roomGridZ < 0 || roomGridX > 5 || roomGridZ > 5) {
    return null;
  }
  const name = String(room.name || "").slice(0, 80);
  const type = String(room.type || "UNKNOWN").replace(/[^A-Z_]/g, "").slice(0, 20) || "UNKNOWN";
  return {
    roomGridX,
    roomGridZ,
    name,
    type,
    secrets: boundedInteger(room.secrets, 0, 99),
    crypts: boundedInteger(room.crypts, 0, 99),
    roomSecretsFound: boundedInteger(room.roomSecretsFound, 0, 99),
    roomSecretsMax: boundedInteger(room.roomSecretsMax, 0, 99),
    visited: Boolean(room.visited),
    cleared: Boolean(room.cleared),
    completed: Boolean(room.completed),
  };
}

function liveDoorFromReport(door) {
  if (!door || !Number.isInteger(Number(door.scanGridX)) || !Number.isInteger(Number(door.scanGridZ))) {
    return null;
  }
  const scanGridX = Number(door.scanGridX);
  const scanGridZ = Number(door.scanGridZ);
  if (scanGridX < 0 || scanGridZ < 0 || scanGridX > 10 || scanGridZ > 10 || (scanGridX % 2) === (scanGridZ % 2)) {
    return null;
  }
  const kind = String(door.kind || "NONE").replace(/[^A-Z_]/g, "").slice(0, 20) || "NONE";
  const targetType = String(door.targetType || "UNKNOWN").replace(/[^A-Z_]/g, "").slice(0, 20) || "UNKNOWN";
  return {
    scanGridX,
    scanGridZ,
    kind,
    targetType,
    targetVisited: Boolean(door.targetVisited),
  };
}

function livePlayerFromReport(player) {
  if (!player) {
    return null;
  }
  const name = String(player.name || "").replace(/[^A-Za-z0-9_]/g, "").slice(0, 16);
  if (name.length < 3) {
    return null;
  }
  return {
    name,
    secretsFound: player.secretsFound == null ? -1 : boundedInteger(player.secretsFound, -1, 250),
    secretsSource: ["API_DELTA", "PERSONAL"].includes(player.secretsSource) ? player.secretsSource : "",
    deaths: boundedInteger(player.deaths, 0, 99),
  };
}

function pruneLiveRuns() {
  const cutoff = Date.now() - liveRunTtlMillis;
  for (const [runKey, run] of liveRuns) {
    for (const [clientKey, client] of run.clients) {
      if (Number(client.updatedAt || 0) < cutoff) {
        run.clients.delete(clientKey);
      }
    }
    if (run.clients.size === 0 || Number(run.updatedAt || 0) < cutoff) {
      liveRuns.delete(runKey);
    }
  }
}

function validateReport(report) {
  if (!report || report.kind !== "kung-room-export") {
    throw new Error("expected kind=kung-room-export");
  }
  if (!report.name || typeof report.name !== "string") {
    throw new Error("report needs a room name");
  }
  if (!report.type || typeof report.type !== "string") {
    throw new Error("report needs a room type");
  }
  if (!Number.isInteger(report.secrets) || report.secrets < 0) {
    throw new Error("report needs a non-negative secrets value");
  }
  if (!Array.isArray(report.cells) || report.cells.length < 1 || report.cells.length > 4) {
    throw new Error("report must contain 1-4 cells");
  }
}

function findOrCreateRoom(database, report) {
  let room = database.rooms.find((candidate) =>
    eq(candidate.name, report.name)
      && candidate.type === report.type
      && Number(candidate.secrets) === Number(report.secrets)
  );
  if (!room) {
    room = {
      name: report.name,
      type: report.type,
      secrets: report.secrets,
      crypts: Number(report.crypts || 0),
      variants: [],
    };
    database.rooms.push(room);
  }
  room.variants ||= [];
  return room;
}

function variantFromReport(report) {
  const minDx = Math.min(...report.cells.map((cell) => Number(cell.dx)));
  const minDz = Math.min(...report.cells.map((cell) => Number(cell.dz)));
  const components = report.cells.map((cell) => ({
    dx: Number(cell.dx) - minDx,
    dz: Number(cell.dz) - minDz,
    hashes: hashesFromReportCell(cell, report),
  }));
  components.sort((a, b) => a.dz - b.dz || a.dx - b.dx);
  if (!connected(components)) {
    throw new Error("room report cells must be connected");
  }
  return { components };
}

function hashesFromReportCell(cell, report) {
  if (!Array.isArray(cell.hashes) || cell.hashes.length < 1) {
    throw new Error("each cell needs at least one hash");
  }
  const hashes = [];
  for (const hash of cell.hashes) {
    const core = Number(hash.coreHash ?? hash.core ?? 0);
    const stable = Number(hash.stableCoreHash ?? hash.stable ?? 0);
    if (!core && !stable) {
      continue;
    }
    const existing = hashes.find((candidate) => candidate.core === core && candidate.stable === stable);
    if (existing) {
      existing.seen += 1;
      existing.updatedAt = Math.max(existing.updatedAt, Number(report.createdAt || Date.now()));
      continue;
    }
    hashes.push({
      core,
      stable,
      seen: 1,
      updatedAt: Number(report.createdAt || Date.now()),
      source: sourceName(hash.source),
    });
  }
  if (hashes.length < 1) {
    throw new Error("each cell needs at least one non-zero hash");
  }
  return hashes;
}

function sourceName(source) {
  const value = String(source || "sync").replace(/[^a-zA-Z0-9_.-]/g, "-");
  return `homeserver-${value}`.slice(0, 80);
}

function sourceKey(source, maxLength) {
  return String(source || "")
    .replace(/[^a-zA-Z0-9_.:-]/g, "-")
    .slice(0, maxLength || 80);
}

function boundedInteger(value, min, max) {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return min;
  }
  return Math.max(min, Math.min(max, Math.trunc(number)));
}

function sameShape(first, second) {
  const firstCells = first.components.map(cellKey).sort();
  const secondCells = second.components.map(cellKey).sort();
  return firstCells.length === secondCells.length
    && firstCells.every((value, index) => value === secondCells[index]);
}

function mergeVariant(target, incoming) {
  for (const incomingComponent of incoming.components) {
    const targetComponent = target.components.find((component) => cellKey(component) === cellKey(incomingComponent));
    if (!targetComponent) {
      target.components.push(incomingComponent);
      continue;
    }
    targetComponent.hashes ||= [];
    for (const incomingHash of incomingComponent.hashes) {
      const targetHash = targetComponent.hashes.find((hash) =>
        Number(hash.core || 0) === incomingHash.core
          && Number(hash.stable || 0) === incomingHash.stable
      );
      if (targetHash) {
        targetHash.seen = Number(targetHash.seen || 1) + incomingHash.seen;
        targetHash.updatedAt = Math.max(Number(targetHash.updatedAt || 0), incomingHash.updatedAt);
      } else {
        targetComponent.hashes.push(incomingHash);
      }
    }
    targetComponent.hashes.sort((a, b) =>
      Number(b.stable || 0) - Number(a.stable || 0)
        || Number(a.core || 0) - Number(b.core || 0)
    );
  }
}

function sortDatabase(database) {
  for (const room of database.rooms) {
    room.variants.sort((a, b) => a.components.length - b.components.length);
    for (const variant of room.variants) {
      variant.components.sort((a, b) => a.dz - b.dz || a.dx - b.dx);
    }
  }
  database.rooms.sort((a, b) =>
    a.name.localeCompare(b.name)
      || a.type.localeCompare(b.type)
      || Number(a.secrets) - Number(b.secrets)
  );
}

function connected(components) {
  const cells = new Set(components.map(cellKey));
  const queue = [components[0]];
  const seen = new Set([cellKey(components[0])]);
  for (let index = 0; index < queue.length; index += 1) {
    const cell = queue[index];
    for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
      const key = `${cell.dx + dx},${cell.dz + dz}`;
      if (!cells.has(key) || seen.has(key)) {
        continue;
      }
      seen.add(key);
      queue.push({ dx: cell.dx + dx, dz: cell.dz + dz });
    }
  }
  return seen.size === cells.size;
}

function cellKey(cell) {
  return `${Number(cell.dx)},${Number(cell.dz)}`;
}

function eq(first, second) {
  return String(first).toLowerCase() === String(second).toLowerCase();
}
