#!/usr/bin/env node
// One-off setup: creates the family document, weekday defaults and example
// recurring items from ../seed/family.json.
//
// Usage (from firebase/functions):
//   gcloud auth application-default login
//   GOOGLE_CLOUD_PROJECT=<your-project-id> npm run seed
//
// Safe to re-run: family settings are merged, defaults/recurring are overwritten
// only for the ids listed in the JSON file.
const path = require("path");
const fs = require("fs");
const { initializeApp, applicationDefault } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");

const file = process.argv[2] || path.join(__dirname, "..", "..", "seed", "family.json");
const seed = JSON.parse(fs.readFileSync(file, "utf8"));

initializeApp({ credential: applicationDefault(), projectId: process.env.GOOGLE_CLOUD_PROJECT });
const db = getFirestore();

async function main() {
  const { familyId, family, defaults = {}, recurring = {} } = seed;
  const people = family.people.map((p) => ({ ...p, email: (p.email || "").toLowerCase() }));
  const memberEmails = people.map((p) => p.email).filter((e) => e && !e.includes("example.com"));
  if (!memberEmails.length) {
    console.warn("⚠  No real emails in seed/family.json yet — nobody will be able to join.");
  }
  const ref = db.collection("families").doc(familyId);
  await ref.set({ ...family, people, memberEmails }, { merge: true });
  for (const [weekday, value] of Object.entries(defaults)) {
    await ref.collection("defaults").doc(weekday).set(value);
  }
  for (const [id, value] of Object.entries(recurring)) {
    await ref.collection("recurring").doc(id).set(value);
  }
  console.log(`✔ Seeded family "${familyId}" with ${people.length} people, ` +
    `${Object.keys(defaults).length} weekday defaults, ${Object.keys(recurring).length} recurring items.`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
