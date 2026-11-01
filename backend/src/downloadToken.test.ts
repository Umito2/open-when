import test from "node:test";
import assert from "node:assert/strict";
import type { File } from "@google-cloud/storage";
import { ensureDownloadToken } from "./downloadToken";

test("existing token is reused without changing upload metadata", async () => {
  const file = {
    getMetadata: async () => [{ metageneration: "1", metadata: { firebaseStorageDownloadTokens: "first,second", caption: "Keep me" } }],
    setMetadata: async () => { assert.fail("Existing tokens must not be rotated."); },
  } as unknown as File;
  assert.equal(await ensureDownloadToken(file), "first");
});

test("simultaneous missing-token opens converge on one token using a generation precondition", async () => {
  let generation = 1;
  let metadata: Record<string, string> = { ownerLabel: "Preserve me" };
  let races = 0;
  const file = {
    getMetadata: async () => [{ metageneration: String(generation), metadata: { ...metadata } }],
    setMetadata: async (update: { metadata: Record<string, string> }, options: { ifMetagenerationMatch: string }) => {
      await Promise.resolve();
      if (Number(options.ifMetagenerationMatch) !== generation) { races++; throw Object.assign(new Error("Changed"), { code: 412 }); }
      metadata = { ...update.metadata };
      generation++;
      return [metadata];
    },
  } as unknown as File;
  const [one, two] = await Promise.all([ensureDownloadToken(file), ensureDownloadToken(file)]);
  assert.equal(one, two);
  assert.equal(metadata.firebaseStorageDownloadTokens, one);
  assert.equal(metadata.ownerLabel, "Preserve me");
  assert.equal(races, 1);
});

test("permission failures are propagated instead of repeatedly rotating tokens", async () => {
  const failure = Object.assign(new Error("Forbidden"), { code: 403 });
  const file = {
    getMetadata: async () => [{ metageneration: "1", metadata: {} }],
    setMetadata: async () => { throw failure; },
  } as unknown as File;
  await assert.rejects(ensureDownloadToken(file), error => error === failure);
});
