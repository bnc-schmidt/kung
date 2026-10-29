#!/usr/bin/env node
// Kung forum bot: every new post in a configured forum channel gets one fixed tag, whatever the
// creator picked. No dependencies (Node 22+: global fetch and WebSocket). See README.md.
import { pathToFileURL } from "node:url";
import { setTimeout as sleep } from "node:timers/promises";

const API = "https://discord.com/api/v10";
const GATEWAY = "wss://gateway.discord.gg/?v=10&encoding=json";
const INTENT_GUILDS = 1; // THREAD_CREATE needs only this one, no privileged intent
const EXIT_CONFIG = 78; // systemd unit sets RestartPreventExitStatus=78: a retry cannot fix it
// Auth failed, invalid or disallowed intents: retrying would burn the 1000 IDENTIFY/day limit.
const FATAL_CLOSE = new Set([4004, 4010, 4011, 4012, 4013, 4014]);

// "<forumChannelId>:<tag name>,<forumChannelId>:<tag name>" -> Map(forumChannelId -> tag name)
export function parseRules(text) {
  const rules = new Map();
  for (const part of String(text ?? "").split(",")) {
    if (!part.trim()) continue;
    const i = part.indexOf(":");
    const channel = part.slice(0, Math.max(i, 0)).trim();
    const tag = part.slice(i + 1).trim();
    if (i < 0 || !/^\d+$/.test(channel) || !tag) {
      throw new Error(`bad rule "${part.trim()}", expected <forumChannelId>:<tag name>`);
    }
    rules.set(channel, tag);
  }
  if (!rules.size) throw new Error("KUNG_FORUM_TAGS has no rule");
  return rules;
}

export function findTagId(forum, name) {
  const want = name.toLowerCase();
  return forum.available_tags?.find((t) => t.name.toLowerCase() === want)?.id;
}

// THREAD_CREATE payload -> true when the post carries the rule's tag afterwards, false when the
// event is not ours or the tag is missing. API errors throw.
export async function handleThreadCreate(thread, rules, api, log = console.log) {
  const tagName = rules.get(thread.parent_id);
  if (!tagName || !thread.newly_created) return false;
  const forum = await api.get(`/channels/${thread.parent_id}`);
  const tagId = findTagId(forum, tagName);
  if (!tagId) {
    log(`forum ${thread.parent_id} has no tag "${tagName}", post ${thread.id} left alone`);
    return false;
  }
  const tags = thread.applied_tags ?? [];
  if (tags.length === 1 && tags[0] === tagId) return true;
  await api.patch(`/channels/${thread.id}`, { applied_tags: [tagId] });
  log(`post ${thread.id} in forum ${thread.parent_id}: tag set to "${tagName}"`);
  return true;
}

function makeApi(token) {
  const call = async (method, path, body, retry = true) => {
    const res = await fetch(API + path, {
      method,
      headers: {
        Authorization: `Bot ${token}`,
        "Content-Type": "application/json",
        "User-Agent": "DiscordBot (https://github.com/bnc-schmidt/kung, 1)",
      },
      body: body && JSON.stringify(body),
    });
    if (res.status === 429 && retry) {
      const { retry_after = 1 } = await res.json().catch(() => ({}));
      await sleep(retry_after * 1000);
      return call(method, path, body, false);
    }
    if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${await res.text()}`);
    return res.json();
  };
  return { get: (path) => call("GET", path), patch: (path, body) => call("PATCH", path, body) };
}

// Setup check at startup: wrong channel id, missing access or a tag typo show up in the log
// now, not at the first post.
async function checkRules(rules, api) {
  for (const [channelId, tagName] of rules) {
    try {
      const forum = await api.get(`/channels/${channelId}`);
      const ok = findTagId(forum, tagName);
      console.log(ok ? `rule ok: forum "${forum.name}" -> "${tagName}"` : `WARNING: forum "${forum.name}" has no tag "${tagName}"`);
    } catch (e) {
      console.log(`WARNING: cannot read forum ${channelId}: ${e.message}`);
    }
  }
}

// ponytail: no RESUME. A post created during a reconnect gap (a second or two) stays untagged;
// add session resume if that ever shows up.
function startGateway(token, onDispatch) {
  let seq = null;
  let attempts = 0;
  const identify = JSON.stringify({
    op: 2,
    d: {
      token,
      intents: INTENT_GUILDS,
      properties: { os: process.platform, browser: "kung-discord-bot", device: "kung-discord-bot" },
    },
  });

  const open = () => {
    const sock = new WebSocket(GATEWAY);
    let beatTimer;
    let acked = true;
    const sendBeat = () => sock.send(JSON.stringify({ op: 1, d: seq }));
    const beat = (interval) => {
      if (!acked) { sock.close(4000); return; } // no ACK since the last beat: dead connection
      acked = false;
      sendBeat();
      beatTimer = setTimeout(beat, interval, interval);
    };

    sock.onmessage = ({ data }) => {
      const { op, d, s, t } = JSON.parse(data);
      if (s != null) seq = s;
      if (op === 10) {
        beatTimer = setTimeout(beat, d.heartbeat_interval * Math.random(), d.heartbeat_interval);
        sock.send(identify);
      } else if (op === 1) sendBeat();
      else if (op === 11) acked = true;
      else if (op === 7 || op === 9) sock.close(4000); // reconnect / invalid session
      else if (op === 0) {
        if (t === "READY") attempts = 0;
        onDispatch(t, d);
      }
    };
    sock.onerror = () => console.log("gateway error");
    sock.onclose = ({ code }) => {
      clearTimeout(beatTimer);
      if (FATAL_CLOSE.has(code)) {
        console.error(`gateway closed with ${code}: check the token and that no privileged intent is requested`);
        process.exit(EXIT_CONFIG);
      }
      const delay = Math.min(60000, 1000 * 2 ** attempts++);
      console.log(`gateway closed (${code}), reconnecting in ${delay / 1000} s`);
      setTimeout(open, delay);
    };
  };
  open();
}

function main() {
  const fail = (msg) => { console.error(msg); process.exit(EXIT_CONFIG); };
  if (typeof WebSocket === "undefined") fail("Node 22 or newer is required (global WebSocket)");
  const token = process.env.DISCORD_BOT_TOKEN;
  if (!token) fail("DISCORD_BOT_TOKEN is not set");
  let rules;
  try { rules = parseRules(process.env.KUNG_FORUM_TAGS); } catch (e) { fail(e.message); }

  const api = makeApi(token);
  startGateway(token, (type, d) => {
    if (type === "READY") {
      console.log(`ready as ${d.user.username}`);
      checkRules(rules, api);
    } else if (type === "THREAD_CREATE") {
      handleThreadCreate(d, rules, api).catch((e) => console.error(`post ${d.id}: ${e.message}`));
    }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main();
