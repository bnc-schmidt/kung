import { test } from "node:test";
import assert from "node:assert/strict";
import { parseRules, findTagId, handleThreadCreate } from "./kung-discord-bot.mjs";

const forum = {
  name: "suggestions",
  available_tags: [
    { id: "11", name: "Under Review" },
    { id: "12", name: "Planned" },
  ],
};

function fakeApi() {
  const calls = [];
  return {
    calls,
    get: async (path) => { calls.push(["GET", path]); return forum; },
    patch: async (path, body) => { calls.push(["PATCH", path, body]); return {}; },
  };
}

const rules = parseRules("100:Under Review, 200 : Investigating");
const post = (extra = {}) => ({ id: "t1", parent_id: "100", newly_created: true, applied_tags: [], ...extra });
const quiet = () => {};

test("parseRules reads rules and trims", () => {
  assert.deepEqual([...rules], [["100", "Under Review"], ["200", "Investigating"]]);
});

test("parseRules rejects bad config", () => {
  for (const bad of ["", "abc", "100", "100:", "x:Tag", "100:A,oops"]) {
    assert.throws(() => parseRules(bad), /rule/, bad);
  }
});

test("findTagId ignores case and returns undefined for unknown tags", () => {
  assert.equal(findTagId(forum, "under review"), "11");
  assert.equal(findTagId(forum, "Nope"), undefined);
});

test("new post gets the fixed tag, replacing the creator's choice", async () => {
  const api = fakeApi();
  assert.equal(await handleThreadCreate(post({ applied_tags: ["12"] }), rules, api, quiet), true);
  assert.deepEqual(api.calls.at(-1), ["PATCH", "/channels/t1", { applied_tags: ["11"] }]);
});

test("post that already has exactly the tag is not patched", async () => {
  const api = fakeApi();
  assert.equal(await handleThreadCreate(post({ applied_tags: ["11"] }), rules, api, quiet), true);
  assert.ok(!api.calls.some(([method]) => method === "PATCH"));
});

test("other forums and old threads are ignored without any API call", async () => {
  const api = fakeApi();
  assert.equal(await handleThreadCreate(post({ parent_id: "999" }), rules, api, quiet), false);
  assert.equal(await handleThreadCreate(post({ newly_created: false }), rules, api, quiet), false);
  assert.equal(await handleThreadCreate(post({ newly_created: undefined }), rules, api, quiet), false);
  assert.deepEqual(api.calls, []);
});

test("missing tag in the forum: logged, nothing patched", async () => {
  const api = fakeApi();
  const lines = [];
  const noTag = parseRules("100:Gone");
  assert.equal(await handleThreadCreate(post(), noTag, api, (l) => lines.push(l)), false);
  assert.ok(!api.calls.some(([method]) => method === "PATCH"));
  assert.match(lines[0], /no tag "Gone"/);
});
