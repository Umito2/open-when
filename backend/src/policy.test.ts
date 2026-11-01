import test from "node:test";
import assert from "node:assert/strict";
import { canPair, openingDecision, validReaction, validSong } from "./policy";
import type { EnvelopePolicy } from "./policy";

const envelope: EnvelopePolicy = { senderId: "adam", receiverId: "noor", unlockRule: "ANYTIME", unlockTimestamp: null, isOpened: false };

test("only sender and recipient can access a letter", () => {
  assert.equal(openingDecision(envelope, "adam", 10), "preview");
  assert.equal(openingDecision(envelope, "noor", 10), "open");
  assert.throws(() => openingDecision(envelope, "stranger", 10));
});
test("date lock uses server time including exact boundary and missing timestamp", () => {
  const dated: EnvelopePolicy = { ...envelope, unlockRule: "DATE_LOCKED", unlockTimestamp: 100 };
  assert.throws(() => openingDecision(dated, "noor", 99));
  assert.equal(openingDecision(dated, "noor", 100), "open");
  assert.throws(() => openingDecision({ ...dated, unlockTimestamp: null }, "noor", 100));
  assert.equal(openingDecision(dated, "adam", 1), "preview");
});
test("one-time letters cannot be reopened by recipient", () => {
  const oneTime: EnvelopePolicy = { ...envelope, unlockRule: "ONE_TIME_ONLY", isOpened: true };
  assert.throws(() => openingDecision(oneTime, "noor", 200));
  assert.equal(openingDecision(oneTime, "adam", 200), "preview");
});
test("reactions require recipient and a previously opened envelope", () => {
  assert.equal(validReaction(envelope, "noor", "❤️"), false);
  assert.equal(validReaction({ ...envelope, isOpened: true }, "noor", "❤️"), true);
  assert.equal(validReaction({ ...envelope, isOpened: true }, "adam", "❤️"), false);
  assert.equal(validReaction({ ...envelope, isOpened: true }, "noor", " "), false);
  assert.equal(validReaction({ ...envelope, isOpened: true }, "noor", "x".repeat(161)), false);
});
test("invitation pairing excludes self, already-paired owner, and joiner with existing space", () => {
  assert.equal(canPair("adam", "noor", false, false), true);
  assert.equal(canPair("adam", "adam", false, false), false);
  assert.equal(canPair("adam", "noor", true, false), false);
  assert.equal(canPair("adam", "noor", false, true), false);
});
test("song links allow HTTPS only", () => {
  assert.equal(validSong("https://open.spotify.com/track/example"), true);
  assert.equal(validSong(null), true);
  assert.equal(validSong("javascript:alert(1)"), false);
  assert.equal(validSong("http://example.com/song"), false);
});
