// node --test "deploy/kung-room-sync/*.test.mjs"
// Runs the real server as a child process against fake Mojang and Hypixel servers.
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { setTimeout as sleep } from "node:timers/promises";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const fixtures = path.join(here, "../../versions/mc26_1_2/src/test/resources/catacombs");
const rawFixture = JSON.parse(fs.readFileSync(path.join(fixtures, "salty-whale-profiles.json"), "utf8"));
const strippedFixture = fs.readFileSync(path.join(fixtures, "salty-whale-profiles-stripped.json"), "utf8");
const fixtureUuid = new URL(rawFixture._source).searchParams.get("uuid");
const dataFile = path.join(os.tmpdir(), `kung-room-sync-test-${process.pid}.json`);
const roomReport = {
  kind: "kung-room-export",
  name: "Test Room",
  type: "NORMAL",
  secrets: 1,
  cells: [{ dx: 0, dz: 0, hashes: [{ coreHash: 1, stableCoreHash: 2 }] }],
};

const owner = { id: "69617dbf568e46329ee980bf67d534d9", name: "Beng114" };
const friend = { id: "0123456789abcdef0123456789abcdef", name: "Friend_1" };
// serverId -> profile; stands in for the client's joinServer call at Mojang.
const joined = new Map();
// Every token and challenge handed out, to prove none of them reaches the server log.
const secrets = [];
const children = [];
let mojang;
let hypixel;
let main;

before(async () => {
  mojang = await listen((request, response) => {
    const url = new URL(request.url, "http://fake");
    const profile = joined.get(url.searchParams.get("serverId"));
    if (url.pathname !== "/session/minecraft/hasJoined" || !profile
      || profile.name.toLowerCase() !== String(url.searchParams.get("username")).toLowerCase()) {
      return response.writeHead(204).end();
    }
    response.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify({ ...profile, properties: [] }));
  });
  hypixel = await listen((request, response) => {
    const uuid = new URL(request.url, "http://fake").searchParams.get("uuid");
    if (request.headers["api-key"] !== "test-key") {
      return response.writeHead(403).end('{"success":false,"cause":"Invalid API key"}');
    }
    if (uuid === "e".repeat(32)) {
      return response.writeHead(502).end("upstream broke");
    }
    if (new URL(request.url, "http://fake").pathname === "/v2/player") {
      const achievements = uuid === fixtureUuid ? { skyblock_treasure_hunter: 4321, other: 1 } : {};
      return response.writeHead(200, { "content-type": "application/json" })
        .end(JSON.stringify({ success: true, player: { achievements, displayname: "junk" } }));
    }
    const body = uuid === fixtureUuid ? withJunk(rawFixture) : { success: true, profiles: null };
    response.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify(body));
  });
  main = await startServer({ KUNG_AUTH_LIMIT_PER_5_MIN: "1000" });
});

after(() => {
  children.forEach((child) => child.kill());
  for (const server of [mojang, hypixel]) {
    server.closeAllConnections();
    server.close();
  }
  fs.rmSync(dataFile, { force: true });
});

test("health needs no session", async () => {
  assert.deepEqual((await call(main, "GET", "/health")).body, { ok: true });
});

test("login returns a session for the Mojang-verified player", async () => {
  const start = Date.now();
  const session = await signIn(main, owner, "beng114");
  assert.equal(session.status, 200);
  assert.match(session.body.token, /^[0-9a-f]{64}$/);
  assert.equal(session.body.uuid, owner.id);
  assert.equal(session.body.name, "Beng114");
  assert.ok(session.body.expiresAt >= start + 43_200_000);
});

test("a challenge is single use", async () => {
  const challenge = await newChallenge(main);
  assert.match(challenge.challenge, /^[0-9a-f]{32}$/);
  assert.equal(challenge.expiresInMs, 60_000);
  joined.set(challenge.challenge, owner);
  assert.equal((await login(main, owner.name, challenge.challenge)).status, 200);
  assert.equal((await login(main, owner.name, challenge.challenge)).status, 401);
});

test("an unverified login is 401 and still burns the challenge", async () => {
  const { challenge } = await newChallenge(main);
  const first = await login(main, owner.name, challenge);
  assert.equal(first.status, 401);
  assert.deepEqual(first.body, { error: "not verified" });
  joined.set(challenge, owner);
  assert.equal((await login(main, owner.name, challenge)).status, 401);
  assert.equal((await login(main, owner.name, "0".repeat(32))).status, 401);
});

test("a challenge expires", async () => {
  const server = await startServer({ KUNG_CHALLENGE_TTL_MS: "50" });
  const { challenge, expiresInMs } = await newChallenge(server);
  assert.equal(expiresInMs, 50);
  joined.set(challenge, owner);
  await sleep(120);
  assert.equal((await login(server, owner.name, challenge)).status, 401);
});

test("any verified player signs in and reads, whatever the lists say", async () => {
  const server = await startServer({ KUNG_ALLOWED_UUIDS: owner.id, KUNG_ROOM_WRITER_UUIDS: owner.id });
  const session = await signIn(server, friend);
  assert.equal(session.status, 200);
  const profiles = await call(server, "GET", `/hypixel/profiles?uuid=${fixtureUuid}`, { token: session.body.token });
  assert.equal(profiles.status, 200);
  assert.equal(profiles.text, strippedFixture);
  assert.equal((await call(server, "GET", "/rooms", { token: session.body.token })).status, 200);
});

test("only writers upload rooms; the old allowlist is the fallback and empty means nobody", async () => {
  const fallback = await startServer({ KUNG_ALLOWED_UUIDS: " 69617DBF-568E-4632-9EE9-80BF67D534D9 , ffffffffffffffffffffffffffffffff" });
  const writer = (await signIn(fallback, owner)).body.token;
  const reader = (await signIn(fallback, friend)).body.token;
  assert.equal((await call(fallback, "POST", "/rooms/report", { token: writer, body: roomReport })).status, 200);
  assert.match(fallback.output(), /any verified player; room uploads: 2 writer\(s\)/);
  const refused = await call(fallback, "POST", "/rooms/report", { token: reader, body: roomReport });
  assert.equal(refused.status, 403);
  assert.deepEqual(refused.body, { error: "not allowed to upload" });

  // The new variable wins over the old one.
  const renamed = await startServer({ KUNG_ALLOWED_UUIDS: owner.id, KUNG_ROOM_WRITER_UUIDS: friend.id });
  const friendToken = (await signIn(renamed, friend)).body.token;
  const ownerToken = (await signIn(renamed, owner)).body.token;
  assert.equal((await call(renamed, "POST", "/rooms/report", { token: friendToken, body: roomReport })).status, 200);
  assert.equal((await call(renamed, "POST", "/rooms/report", { token: ownerToken, body: roomReport })).status, 403);

  // Set but blank means nobody, even with the old variable present.
  const blank = await startServer({ KUNG_ALLOWED_UUIDS: owner.id, KUNG_ROOM_WRITER_UUIDS: "" });
  const blankToken = (await signIn(blank, owner)).body.token;
  assert.equal((await call(blank, "POST", "/rooms/report", { token: blankToken, body: roomReport })).status, 403);

  // main has no lists at all.
  const nobody = (await signIn(main, owner)).body.token;
  assert.equal((await call(main, "POST", "/rooms/report", { token: nobody, body: roomReport })).status, 403);
});

test("rate limits are per signed-in player, not per IP", async () => {
  const server = await startServer({
    KUNG_PROFILE_USER_LIMIT_PER_5_MIN: "2",
    KUNG_LIVE_REPORT_LIMIT_PER_5_MIN: "1",
    KUNG_ROOM_REPORT_LIMIT_PER_5_MIN: "1",
    KUNG_ROOM_WRITER_UUIDS: `${owner.id},${friend.id}`,
  });
  const live = { kind: "kung-live-room-sync", runKey: "run-1", players: [{ name: "Beng114" }] };
  const requests = [
    ["GET", `/hypixel/profiles?uuid=${fixtureUuid}`, undefined, 2],
    ["POST", "/runs/live/report", live, 1],
    ["POST", "/rooms/report", roomReport, 1],
  ];
  const ownerToken = (await signIn(server, owner)).body.token;
  const friendToken = (await signIn(server, friend)).body.token;
  for (const [method, route, body, allowed] of requests) {
    for (let index = 0; index < allowed; index += 1) {
      assert.equal((await call(server, method, route, { token: ownerToken, body })).status, 200, `${route} #${index}`);
    }
    const limited = await call(server, method, route, { token: ownerToken, body });
    assert.equal(limited.status, 429, route);
    assert.deepEqual(limited.body, { error: "too many requests" });
    // Same IP, other player: unaffected.
    assert.equal((await call(server, method, route, { token: friendToken, body })).status, 200, route);
  }
});

test("signing in again replaces the player's earlier session", async () => {
  const first = (await signIn(main, owner)).body.token;
  const other = (await signIn(main, friend)).body.token;
  assert.equal((await call(main, "GET", "/rooms", { token: first })).status, 200);
  const second = (await signIn(main, owner)).body.token;
  assert.notEqual(second, first);
  assert.equal((await call(main, "GET", "/rooms", { token: first })).status, 401);
  assert.equal((await call(main, "GET", "/rooms", { token: second })).status, 200);
  assert.equal((await call(main, "GET", "/rooms", { token: other })).status, 200);
});

test("every other endpoint needs a valid session", async () => {
  const routes = [
    ["GET", "/rooms"],
    ["POST", "/rooms/report"],
    ["GET", "/runs/live?runKey=run"],
    ["POST", "/runs/live/report"],
    ["GET", `/hypixel/profiles?uuid=${fixtureUuid}`],
  ];
  for (const [method, route] of routes) {
    for (const token of [undefined, "f".repeat(64), "junk"]) {
      const response = await call(main, method, route, { token, body: method === "POST" ? {} : undefined });
      assert.equal(response.status, 401, `${method} ${route} with ${token}`);
      assert.deepEqual(response.body, { error: "unauthorized" });
    }
  }
});

test("live runs use the session's name, not the report's or the query's", async () => {
  const ownerToken = (await signIn(main, owner)).body.token;
  const friendToken = (await signIn(main, friend)).body.token;
  const report = await call(main, "POST", "/runs/live/report", {
    token: ownerToken,
    body: { kind: "kung-live-room-sync", runKey: "run-1", player: "Victim", players: [{ name: "Victim", deaths: 1 }] },
  });
  assert.equal(report.status, 200);
  assert.equal(report.body.player, "Beng114");

  // The player query must not hide the owner from the friend, nor the owner's own report from the owner.
  const forFriend = await call(main, "GET", "/runs/live?runKey=run-1&player=Beng114", { token: friendToken });
  assert.deepEqual(forFriend.body.clients.map((client) => client.player), ["Beng114"]);
  const forOwner = await call(main, "GET", "/runs/live?runKey=run-1&player=Friend_1", { token: ownerToken });
  assert.deepEqual(forOwner.body.clients, []);
});

test("profiles are stripped to what the Java parser reads", async () => {
  const token = (await signIn(main, owner)).body.token;
  const profiles = await call(main, "GET", `/hypixel/profiles?uuid=${fixtureUuid}`, { token });
  assert.equal(profiles.status, 200);
  // The Java test compares the parser's results on the raw and this stripped file.
  assert.equal(profiles.text, strippedFixture);
  // The fixture holds only fields the parser reads, so stripping must return it minus the metadata.
  const { _source, _accessed, _note, ...expected } = rawFixture;
  assert.deepEqual(profiles.body, expected);

  const none = await call(main, "GET", `/hypixel/profiles?uuid=${"0".repeat(32)}`, { token });
  assert.deepEqual(none.body, { success: true, profiles: null });
  const broken = await call(main, "GET", `/hypixel/profiles?uuid=${"e".repeat(32)}`, { token });
  assert.equal(broken.status, 502);
  assert.equal(broken.text, "upstream broke");
});

test("secrets are the lifetime achievement alone, -1 when the player has none", async () => {
  const token = (await signIn(main, owner)).body.token;
  const secrets = await call(main, "GET", `/hypixel/secrets?uuid=${fixtureUuid}`, { token });
  assert.equal(secrets.status, 200);
  assert.deepEqual(secrets.body, { success: true, secrets: 4321 });
  assert.deepEqual((await call(main, "GET", `/hypixel/secrets?uuid=${"0".repeat(32)}`, { token })).body,
    { success: true, secrets: -1 });
  assert.equal((await call(main, "GET", `/hypixel/secrets?uuid=${"e".repeat(32)}`, { token })).status, 502);
  assert.equal((await call(main, "GET", "/hypixel/secrets?uuid=nope", { token })).status, 400);
  assert.equal((await call(main, "GET", `/hypixel/secrets?uuid=${fixtureUuid}`, {})).status, 401);
});

test("each profile lookup logs one line, never the raw query", async () => {
  const token = (await signIn(main, owner)).body.token;
  const uuid = "1".repeat(32);
  await call(main, "GET", `/hypixel/profiles?uuid=${uuid}`, { token });
  await call(main, "GET", `/hypixel/profiles?uuid=${uuid}`, { token });
  await call(main, "GET", "/hypixel/profiles?uuid=%0Aforged", { token });
  for (let tries = 0; tries < 50 && !main.output().includes("profiles ? for Beng114: bad uuid 400"); tries += 1) {
    await sleep(20);
  }
  const lines = main.output().split("\n").filter((line) => line.startsWith("profiles "));
  assert.deepEqual(lines.slice(-3), [
    `profiles ${uuid} for Beng114: hypixel 200`,
    `profiles ${uuid} for Beng114: cache hit 200`,
    "profiles ? for Beng114: bad uuid 400",
  ]);
  assert.ok(!main.output().includes("forged"));
});

test("auth is rate limited per IP", async () => {
  const server = await startServer({ KUNG_AUTH_LIMIT_PER_5_MIN: "3" });
  for (let index = 0; index < 3; index += 1) {
    assert.equal((await call(server, "POST", "/auth/challenge")).status, 200);
  }
  assert.equal((await call(server, "POST", "/auth/challenge")).status, 429);
  assert.equal((await login(server, owner.name, "0".repeat(32))).status, 429);
});

test("the log never shows a token or challenge", () => {
  assert.ok(secrets.length > 5);
  for (const secret of secrets) {
    assert.ok(!main.output().includes(secret));
  }
});

// The real fixture plus fields and members the parser never reads.
function withJunk(root) {
  const copy = structuredClone(root);
  copy.cause = "junk";
  for (const profile of copy.profiles) {
    profile.banking = { balance: 1e9 };
    profile.members[friend.id] = { dungeons: { secrets: 1 } };
    const member = profile.members[fixtureUuid];
    member.inventory = { inv_contents: { data: "junk" } };
    member.player_data.experience = { SKILL_MINING: 1 };
    if (member.dungeons) {
      member.dungeons.treasures = { runs: [] };
      member.dungeons.dungeon_types.catacombs.tier_completions = { 7: 1 };
    }
  }
  return copy;
}

async function startServer(env) {
  const child = spawn(process.execPath, [path.join(here, "kung-room-sync-server.mjs")], {
    env: {
      ...process.env,
      PORT: "0",
      KUNG_ROOM_SYNC_HOST: "127.0.0.1",
      KUNG_ROOM_DATA_PATH: dataFile,
      KUNG_MOJANG_SESSION_URL: `http://127.0.0.1:${mojang.address().port}`,
      KUNG_HYPIXEL_API_URL: `http://127.0.0.1:${hypixel.address().port}`,
      HYPIXEL_API_KEY: "test-key",
      KUNG_ALLOWED_UUIDS: "",
      KUNG_ROOM_WRITER_UUIDS: undefined, // unset, so it falls back to KUNG_ALLOWED_UUIDS
      KUNG_CHALLENGE_TTL_MS: "",
      KUNG_SESSION_TTL_MS: "",
      KUNG_AUTH_LIMIT_PER_5_MIN: "",
      KUNG_PROFILE_LIMIT_PER_5_MIN: "",
      KUNG_PROFILE_USER_LIMIT_PER_5_MIN: "",
      KUNG_LIVE_REPORT_LIMIT_PER_5_MIN: "",
      KUNG_ROOM_REPORT_LIMIT_PER_5_MIN: "",
      ...env,
    },
  });
  children.push(child);
  let output = "";
  const port = await new Promise((resolve, reject) => {
    child.stdout.on("data", (chunk) => {
      output += chunk;
      const match = /listening on [^\n]*:(\d+)/.exec(output);
      if (match) {
        resolve(Number(match[1]));
      }
    });
    child.stderr.on("data", (chunk) => {
      output += chunk;
    });
    child.on("exit", (code) => reject(new Error(`server exited with ${code}: ${output}`)));
  });
  return { url: `http://127.0.0.1:${port}`, output: () => output };
}

async function newChallenge(server) {
  const response = await call(server, "POST", "/auth/challenge");
  assert.equal(response.status, 200);
  secrets.push(response.body.challenge);
  return response.body;
}

async function login(server, name, challenge) {
  const response = await call(server, "POST", "/auth/login", { body: { name, challenge } });
  if (response.body?.token) {
    secrets.push(response.body.token);
  }
  return response;
}

async function signIn(server, profile, name = profile.name) {
  const { challenge } = await newChallenge(server);
  joined.set(challenge, profile);
  return login(server, name, challenge);
}

async function call(server, method, route, { body, token } = {}) {
  const headers = { "content-type": "application/json" };
  if (token) {
    headers.authorization = `Bearer ${token}`;
  }
  const response = await fetch(server.url + route, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let parsed = null;
  try {
    parsed = JSON.parse(text);
  } catch {
    // Upstream passthrough bodies need not be JSON.
  }
  return { status: response.status, text, body: parsed };
}

function listen(handler) {
  const server = http.createServer(handler);
  return new Promise((resolve) => server.listen(0, "127.0.0.1", () => resolve(server)));
}
