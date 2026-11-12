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
// A Windows checkout may turn the fixture's \n into \r\n; the server always answers with \n.
const strippedFixture = fs.readFileSync(path.join(fixtures, "salty-whale-profiles-stripped.json"), "utf8").replace(/\r\n/g, "\n");
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
// A player whose profiles hold Sack counts; the fake Hypixel answers for this uuid with two profiles.
const sacker = { id: "5".repeat(32), name: "Sack_Player" };
// The players a !bestiary command may name; the fake Mojang name lookup knows them.
const broken = { id: "e".repeat(32), name: "Broken_One" };
const lonely = { id: "0".repeat(32), name: "Lonely_One" };
const namedPlayers = [sacker, friend, broken, lonely, { id: fixtureUuid, name: "Fixture_Player" }];
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
    const lookedUp = url.pathname.match(/^\/minecraft\/profile\/lookup\/name\/(.+)$/);
    if (lookedUp) {
      const known = namedPlayers.find((player) => player.name.toLowerCase() === decodeURIComponent(lookedUp[1]).toLowerCase());
      return known ? response.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify(known))
        : response.writeHead(404).end('{"errorMessage":"Couldn\'t find any profile with that name"}');
    }
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
    const body = uuid === fixtureUuid ? withJunk(rawFixture) : uuid === sacker.id ? sackProfiles() : { success: true, profiles: null };
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

test("Jerry's cycle is pooled from readings, kept per term and survives a restart", async () => {
  const env = { KUNG_ROOM_DATA_PATH: path.join(os.tmpdir(), `kung-jerry-test-${process.pid}.json`) };
  const jerryFile = env.KUNG_ROOM_DATA_PATH.replace(/\.json$/, ".jerry.json");
  fs.rmSync(jerryFile, { force: true });
  const server = await startServer(env);
  const token = (await signIn(server, owner)).body.token;
  const hour = 3_600_000;
  const t0 = Date.now() - 2 * hour;
  const report = (body) => call(server, "POST", "/jerry/report", { token, body });

  assert.deepEqual((await call(server, "GET", "/jerry?year=520", { token })).body.positions, Array(6).fill(null));
  const first = await report({ year: 520, mayor: "Diana", slotStart: t0 });
  assert.equal(first.status, 200);
  assert.deepEqual(first.body, { year: 520, anchor: t0, positions: ["Diana", null, null, null, null, null] });

  // Another player's reading 4 slots later, one minute off, lands in position 4; Diana moves when seen again.
  const second = await report({ year: 520, mayor: "Paul", slotStart: t0 + 4 * 6 * hour - 60_000 });
  assert.deepEqual(second.body.positions, ["Diana", null, null, null, "Paul", null]);
  const moved = await report({ year: 520, mayor: "Diana", slotStart: t0 + 6 * hour });
  assert.deepEqual(moved.body.positions, [null, "Diana", null, null, "Paul", null]);
  assert.deepEqual((await call(server, "GET", "/jerry?year=520", { token })).body, moved.body);
  assert.deepEqual((await call(server, "GET", "/jerry?year=528", { token })).body.positions, Array(6).fill(null));

  // An older term is ignored, a newer one starts over.
  assert.deepEqual((await report({ year: 496, mayor: "Cole", slotStart: t0 })).body, moved.body);
  assert.deepEqual((await report({ year: 528, mayor: "Cole", slotStart: t0 })).body,
    { year: 528, anchor: t0, positions: ["Cole", null, null, null, null, null] });

  const restarted = await startServer(env);
  const again = (await signIn(restarted, owner)).body.token;
  assert.equal((await call(restarted, "GET", "/jerry?year=528", { token: again })).body.positions[0], "Cole");
  fs.rmSync(jerryFile, { force: true });
});

test("Jerry reports are validated and need a session", async () => {
  const token = (await signIn(main, owner)).body.token;
  const now = Date.now();
  for (const body of [{}, null, { year: 520, mayor: "Derpy", slotStart: now }, { year: "520", mayor: "Paul", slotStart: now },
    { year: -1, mayor: "Paul", slotStart: now }, { year: 520, mayor: "Paul" },
    { year: 520, mayor: "Paul", slotStart: now + 30 * 86_400_000 }]) {
    assert.equal((await call(main, "POST", "/jerry/report", { token, body })).status, 400, JSON.stringify(body));
  }
  for (const [method, route] of [["POST", "/jerry/report"], ["GET", "/jerry?year=520"]]) {
    assert.equal((await call(main, method, route, { body: method === "POST" ? {} : undefined })).status, 401, route);
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
    ["GET", "/bestiary?name=Sack_Player&mob=barb"],
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

test("sacks are your own counts of the selected profile alone", async () => {
  const token = (await signIn(main, sacker)).body.token;
  const sacks = await call(main, "GET", `/hypixel/sacks?uuid=${sacker.id}`, { token });
  assert.equal(sacks.status, 200);
  assert.deepEqual(sacks.body, {
    success: true,
    profiles: [{
      profile_id: "b",
      cute_name: "Banana",
      selected: true,
      members: { [sacker.id]: { inventory: { sacks_counts: { WHEAT: 1500, CARROT_ITEM: 20 } } } },
    }],
  });
  // Dashes in the uuid are fine, someone else's uuid is not (even the public fixture player).
  assert.equal((await call(main, "GET", `/hypixel/sacks?uuid=${sacker.id.replace(/(.{8})(.*)/, "$1-$2")}`, { token })).status, 200);
  const other = await call(main, "GET", `/hypixel/sacks?uuid=${fixtureUuid}`, { token });
  assert.equal(other.status, 403);
  assert.deepEqual(other.body, { error: "sacks of your own account only" });
  assert.equal((await call(main, "GET", "/hypixel/sacks?uuid=nope", { token })).status, 403);
  assert.equal((await call(main, "GET", `/hypixel/sacks?uuid=${sacker.id}`, {})).status, 401);
});

const bestiary = (server, token, name, mob, profile = "") =>
  call(server, "GET", `/bestiary?name=${encodeURIComponent(name)}&mob=${encodeURIComponent(mob)}${profile ? `&profile=${profile}` : ""}`, { token });

test("bestiary answers with the kills and levels of the selected profile", async () => {
  const token = (await signIn(main, friend)).body.token;
  // The name is fixed to Mojang's spelling; a short word finds the mob; the unselected profile's 5 zombie kills do not count.
  const duke = await bestiary(main, token, "sack_player", "barb");
  assert.equal(duke.status, 200);
  assert.deepEqual(duke.body, { success: true, message: "Sack_Player | Barbarian Duke X: 7 kills | Lvl 5 | 2 to next | 93 to max" });
  assert.equal((await bestiary(main, token, "Sack_Player", "Barbarian Duke X")).body.message, duke.body.message);
  assert.equal((await bestiary(main, token, "Sack_Player", "zombie")).body.message,
    "Sack_Player | Zombie: 12 kills | Lvl 2 | 3 to next | 38 to max");
  // At the cap only the kills: a Bat in the Catacombs maxes out at 1000.
  assert.equal((await bestiary(main, token, "Sack_Player", "bat cata")).body.message, "Sack_Player | Bat (Catacombs): 1,500 kills");
});

test("bestiary tells a variant apart from the mob that holds it", async () => {
  const token = (await signIn(main, friend)).body.token;
  // Stoneworm is the game's one entry (Worm and Scatha: 43 kills); Scatha alone shows its 3, the level is Stoneworm's.
  const stone = (await bestiary(main, token, "Sack_Player", "stoneworm")).body.message;
  assert.match(stone, /^Sack_Player \| Stoneworm: 43 kills \| Lvl \d+ \| [\d,]+ to next \| [\d,]+ to max$/);
  const scatha = (await bestiary(main, token, "Sack_Player", "scatha")).body.message;
  assert.equal(scatha, stone.replace("| Stoneworm: 43 kills", "| Scatha: 3 kills | Stoneworm: 43 kills"));
  assert.equal((await bestiary(main, token, "Sack_Player", "scat")).body.message, scatha);
});

test("bestiary reads a named profile and says which one", async () => {
  const token = (await signIn(main, friend)).body.token;
  // The unselected Apple profile has 5 zombie kills, the selected Banana 12; the name matches in any case.
  assert.equal((await bestiary(main, token, "Sack_Player", "zombie", "Apple")).body.message,
    "Sack_Player (Apple) | Zombie: 5 kills | Lvl 1 | 5 to next | 45 to max");
  assert.equal((await bestiary(main, token, "Sack_Player", "zombie", "banana")).body.message,
    "Sack_Player (Banana) | Zombie: 12 kills | Lvl 2 | 3 to next | 38 to max");
  assert.equal((await bestiary(main, token, "Sack_Player", "zombie", "Mango")).body.message,
    "Sack_Player has no Mango profile (has Apple, Banana).");
  // Without the profile the selected one answers, with no name in the line.
  assert.equal((await bestiary(main, token, "Sack_Player", "zombie")).body.message,
    "Sack_Player | Zombie: 12 kills | Lvl 2 | 3 to next | 38 to max");
  // Only letters, at most 20: nothing else reaches the line.
  for (const bad of ["Coco1", "Co%20co", "x".repeat(21), "%3Cb%3E"]) {
    assert.equal((await bestiary(main, token, "Sack_Player", "zombie", bad)).status, 400, bad);
  }
});

test("bestiary lists the best three when the name fits several mobs and spends no lookup", async () => {
  const token = (await signIn(main, friend)).body.token;
  // An unknown player gets the list too: nothing reached Mojang or Hypixel.
  const bats = await bestiary(main, token, "Nobody_Here", "bat");
  assert.equal(bats.body.message, "Which one? Bat (Catacombs), Bat (Private Island). Type more of the name.");
  const b = (await bestiary(main, token, "Nobody_Here", "b")).body.message;
  assert.match(b, /^Which one\? [^,]+, [^,]+, [^,]+ \(\+\d+ more\)\. Type more of the name\.$/);
  assert.equal((await bestiary(main, token, "Nobody_Here", "xyzzy")).body.message, "No Bestiary mob fits that name.");
  assert.equal((await bestiary(main, token, "Nobody_Here", "")).body.message, "No Bestiary mob fits that name.");
  // The typed words never come back.
  assert.ok(!(await bestiary(main, token, "Nobody_Here", "xyzzy")).body.message.includes("xyzzy"));
});

test("bestiary tells why there is no number", async () => {
  const token = (await signIn(main, friend)).body.token;
  assert.equal((await bestiary(main, token, "Nobody_Here", "barb")).body.message, "No player named Nobody_Here.");
  assert.equal((await bestiary(main, token, "Lonely_One", "barb")).body.message, "No SkyBlock profile for Lonely_One.");
  assert.equal((await bestiary(main, token, "Fixture_Player", "barb")).body.message, "No Bestiary data for Fixture_Player.");
  assert.equal((await bestiary(main, token, "Broken_One", "barb")).body.message,
    "Could not look up the Bestiary of Broken_One: Hypixel is unavailable.");
  assert.equal((await bestiary(main, token, "bad name!", "barb")).status, 400);
  assert.equal((await bestiary(main, token, "Sack_Player", "x".repeat(41))).status, 400);
  assert.equal((await call(main, "GET", "/bestiary?name=Sack_Player&mob=barb", {})).status, 401);
});

test("bestiary has its own limits and only lookups at Hypixel use the shared share", async () => {
  const server = await startServer({ KUNG_BESTIARY_USER_LIMIT_PER_5_MIN: "3", KUNG_BESTIARY_ALL_LIMIT_PER_5_MIN: "1" });
  const token = (await signIn(server, friend)).body.token;
  assert.equal((await bestiary(server, token, "Sack_Player", "barb")).status, 200);
  assert.equal((await bestiary(server, token, "Sack_Player", "zombie")).status, 429);
  // An unclear name needs no share; the fourth request is over the per-player limit of 3.
  assert.equal((await bestiary(server, token, "Sack_Player", "bat")).status, 200);
  assert.equal((await bestiary(server, token, "Sack_Player", "bat")).status, 429);
  // Profiles keep their own budget.
  assert.equal((await call(server, "GET", `/hypixel/profiles?uuid=${fixtureUuid}`, { token })).status, 200);
});

test("the bundled Bestiary list is consistent", () => {
  const list = JSON.parse(fs.readFileSync(path.join(here, "kung-bestiary.json"), "utf8"));
  const ids = new Set();
  assert.ok(list.mobs.length > 300);
  for (const mob of list.mobs) {
    // A variant is a named part of the mob: its ids are some of the mob's own.
    for (const [variant, variantIds] of Object.entries(mob.variants ?? {})) {
      assert.ok(variantIds.length > 0 && variantIds.every((id) => mob.ids.includes(id)), `${variant} is part of ${mob.name}`);
    }
    const brackets = (mob.critter ? list.critterBrackets : list.brackets)[mob.bracket];
    assert.ok(brackets?.includes(mob.cap), `${mob.name}: the cap ${mob.cap} is a level of bracket ${mob.bracket}`);
    assert.ok(mob.ids.length > 0, `${mob.name} has ids`);
    for (const id of mob.ids) {
      assert.ok(!ids.has(id), `${id} belongs to one mob`);
      ids.add(id);
    }
  }
  // Two entries share a name only where they carry an area, so a typed name can still tell them apart.
  const byName = Object.groupBy(list.mobs, (mob) => mob.name);
  for (const [name, mobs] of Object.entries(byName)) {
    assert.ok(mobs.length === 1 || mobs.every((mob) => mob.area), `${name} needs areas`);
  }
});

test("sacks of a player without profiles stay empty and sacks have their own limit", async () => {
  const server = await startServer({ KUNG_SACKS_USER_LIMIT_PER_5_MIN: "2" });
  const lonely = { id: "0".repeat(32), name: "Lonely_One" };
  const token = (await signIn(server, lonely)).body.token;
  assert.deepEqual((await call(server, "GET", `/hypixel/sacks?uuid=${lonely.id}`, { token })).body, { success: true, profiles: null });
  assert.equal((await call(server, "GET", `/hypixel/sacks?uuid=${lonely.id}`, { token })).status, 200);
  assert.equal((await call(server, "GET", `/hypixel/sacks?uuid=${lonely.id}`, { token })).status, 429);
  // The profile route keeps its own budget.
  assert.equal((await call(server, "GET", `/hypixel/profiles?uuid=${fixtureUuid}`, { token })).status, 200);
});

test("all players together get a fixed share of the Hypixel key for sacks", async () => {
  const server = await startServer({ KUNG_SACKS_ALL_LIMIT_PER_5_MIN: "2" });
  const lonely = { id: "0".repeat(32), name: "Lonely_One" };
  const tokens = [];
  for (const player of [sacker, lonely, friend]) {
    tokens.push((await signIn(server, player)).body.token);
  }
  assert.equal((await call(server, "GET", `/hypixel/sacks?uuid=${sacker.id}`, { token: tokens[0] })).status, 200);
  assert.equal((await call(server, "GET", `/hypixel/sacks?uuid=${lonely.id}`, { token: tokens[1] })).status, 200);
  const limited = await call(server, "GET", `/hypixel/sacks?uuid=${friend.id}`, { token: tokens[2] });
  assert.equal(limited.status, 429);
  assert.deepEqual(limited.body, { error: "too many requests" });
  // Profiles and secrets keep their own budgets.
  assert.equal((await call(server, "GET", `/hypixel/secrets?uuid=${fixtureUuid}`, { token: tokens[2] })).status, 200);
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

test("CF-Connecting-IP counts only from a loopback connection", async (t) => {
  const asIp = (ip) => ({ "cf-connecting-ip": ip });
  const behindTunnel = await startServer({ KUNG_AUTH_LIMIT_PER_5_MIN: "1" });
  assert.equal((await call(behindTunnel, "POST", "/auth/challenge", { headers: asIp("1.1.1.1") })).status, 200);
  assert.equal((await call(behindTunnel, "POST", "/auth/challenge", { headers: asIp("1.1.1.1") })).status, 429);
  assert.equal((await call(behindTunnel, "POST", "/auth/challenge", { headers: asIp("2.2.2.2") })).status, 200);

  const external = Object.values(os.networkInterfaces()).flat()
    .find((address) => address && address.family === "IPv4" && !address.internal)?.address;
  if (!external) {
    return t.skip("no non-loopback address on this machine");
  }
  const direct = await startServer({ KUNG_AUTH_LIMIT_PER_5_MIN: "1", KUNG_ROOM_SYNC_HOST: "0.0.0.0" });
  direct.url = direct.url.replace("127.0.0.1", external);
  assert.equal((await call(direct, "POST", "/auth/challenge", { headers: asIp("1.1.1.1") })).status, 200);
  assert.equal((await call(direct, "POST", "/auth/challenge", { headers: asIp("2.2.2.2") })).status, 429);
});

test("authenticated but malformed reports are 400 and leave no stack in the log", async () => {
  const server = await startServer({ KUNG_ROOM_WRITER_UUIDS: owner.id });
  const token = (await signIn(server, owner)).body.token;
  const cell = (hashes) => ({ ...roomReport, cells: [{ dx: 0, dz: 0, hashes }] });
  const rooms = [null, [], {}, { ...roomReport, name: "" }, { ...roomReport, cells: [] }, { ...roomReport, cells: [null] },
    cell([]), cell([null]), cell([{ coreHash: 0, stableCoreHash: 0 }])];
  for (const body of rooms) {
    assert.equal((await call(server, "POST", "/rooms/report", { token, body })).status, 400, JSON.stringify(body));
  }
  for (const body of [null, [], {}, { kind: "kung-live-room-sync" }, { kind: "kung-live-room-sync", runKey: "r" },
    { kind: "kung-live-room-sync", runKey: "r", rooms: {} }]) {
    assert.equal((await call(server, "POST", "/runs/live/report", { token, body })).status, 400, JSON.stringify(body));
  }
  assert.ok(!/\bat .*\.mjs/.test(server.output()), server.output());
});

test("parallel room reports all land in the file", async () => {
  const file = path.join(os.tmpdir(), `kung-parallel-test-${process.pid}.json`);
  fs.rmSync(file, { force: true });
  const server = await startServer({
    KUNG_ROOM_DATA_PATH: file,
    KUNG_ROOM_WRITER_UUIDS: owner.id,
    KUNG_ROOM_REPORT_LIMIT_PER_5_MIN: "1000",
  });
  const token = (await signIn(server, owner)).body.token;
  const names = Array.from({ length: 25 }, (_, index) => `Room ${index}`);
  const replies = await Promise.all(names.map((name) =>
    call(server, "POST", "/rooms/report", { token, body: { ...roomReport, name } })));
  assert.deepEqual(replies.map((reply) => reply.status), names.map(() => 200));
  const saved = JSON.parse(fs.readFileSync(file, "utf8"));
  assert.deepEqual(saved.rooms.map((room) => room.name).sort(), [...names].sort());
  assert.deepEqual(fs.readdirSync(path.dirname(file)).filter((entry) => entry.startsWith(path.basename(file)) && entry.endsWith(".tmp")), []);
  fs.rmSync(file, { force: true });
});

test("the log never shows a token or challenge", () => {
  assert.ok(secrets.length > 5);
  for (const secret of secrets) {
    assert.ok(!main.output().includes(secret));
  }
});

// Two profiles of one player with Sack counts (the selected one second) plus coop data and fields nobody should get.
function sackProfiles() {
  const member = (sacks, kills = {}) => ({
    inventory: { inv_contents: { data: "junk" }, sacks_counts: sacks },
    bestiary: { kills, deaths: { zombie_1: 99 }, migrated_stats: true },
    player_data: { junk: 1 },
  });
  return {
    success: true,
    cause: "junk",
    profiles: [
      { profile_id: "a", cute_name: "Apple", selected: false, banking: { balance: 1 },
        members: { [sacker.id]: member({ WHEAT: 1 }, { zombie_1: 5 }) } },
      { profile_id: "b", cute_name: "Banana", selected: true, banking: { balance: 2 },
        members: { [sacker.id]: member({ WHEAT: 1500, CARROT_ITEM: 20 }, { zombie_1: 12, zombie_2: 0, barbarian_duke_x_200: 7, dungeon_secret_bat_1: 1500, worm_5: 40, scatha_10: 3 }),
          [friend.id]: member({ WHEAT: 999 }, { zombie_1: 1 }) } },
    ],
  };
}

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
      KUNG_MOJANG_API_URL: `http://127.0.0.1:${mojang.address().port}`,
      KUNG_BESTIARY_CACHE_MS: "",
      KUNG_BESTIARY_LIMIT_PER_5_MIN: "",
      KUNG_BESTIARY_USER_LIMIT_PER_5_MIN: "",
      KUNG_BESTIARY_ALL_LIMIT_PER_5_MIN: "",
      KUNG_HYPIXEL_API_URL: `http://127.0.0.1:${hypixel.address().port}`,
      HYPIXEL_API_KEY: "test-key",
      KUNG_ALLOWED_UUIDS: "",
      KUNG_ROOM_WRITER_UUIDS: undefined, // unset, so it falls back to KUNG_ALLOWED_UUIDS
      KUNG_CHALLENGE_TTL_MS: "",
      KUNG_SESSION_TTL_MS: "",
      KUNG_AUTH_LIMIT_PER_5_MIN: "",
      KUNG_PROFILE_LIMIT_PER_5_MIN: "",
      KUNG_PROFILE_USER_LIMIT_PER_5_MIN: "",
      KUNG_SACKS_CACHE_MS: "",
      KUNG_SACKS_LIMIT_PER_5_MIN: "",
      KUNG_SACKS_USER_LIMIT_PER_5_MIN: "",
      KUNG_SACKS_ALL_LIMIT_PER_5_MIN: "",
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

async function call(server, method, route, { body, token, headers: extra } = {}) {
  const headers = { "content-type": "application/json", ...extra };
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
