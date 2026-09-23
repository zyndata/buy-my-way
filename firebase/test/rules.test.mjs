// Realtime Database rules tests (PLAN.md Phase 4, task 3; STATE.md decision 56).
//
// Run with `npm test` in this directory: `firebase emulators:exec` starts the database emulator
// under a demo project id (no login, never the real project) and runs this file with node:test.
// Phase 4 covered a single user's own lists; Phase 5 adds members, roles, invites, presence
// and the photo cleanup (STATE.md decisions 63–67).

import { readFileSync } from 'node:fs';
import { after, before, beforeEach, describe, test } from 'node:test';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';

const TIMESTAMP = { '.sv': 'timestamp' };
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const LIST = 'list-1';
const ITEM = 'item-1';
const T0 = 1_700_000_000_000;

let env;

before(async () => {
  const [host, port] = (process.env.FIREBASE_DATABASE_EMULATOR_HOST ?? '127.0.0.1:9000').split(':');
  env = await initializeTestEnvironment({
    projectId: 'demo-buy-my-way',
    database: {
      host,
      port: Number(port),
      rules: readFileSync(new URL('../database.rules.json', import.meta.url), 'utf8'),
    },
  });
});

after(async () => {
  await env?.cleanup();
});

beforeEach(async () => {
  await env.clearDatabase();
});

const db = (uid) => env.authenticatedContext(uid).database();
const withEmail = (uid, email, verified = true) =>
  env.authenticatedContext(uid, { email, email_verified: verified }).database();
const anonymous = () => env.unauthenticatedContext().database();

/** Seeds data as an admin, past the rules. */
async function seed(path, value) {
  await env.withSecurityRulesDisabled((ctx) => ctx.database().ref(path).set(value));
}

function meta(overrides = {}) {
  return {
    name: 'Zakupy',
    ownerUid: ALICE,
    categoryOrder: ['warzywa', 'nabial', 'inne'],
    createdAt: T0,
    updatedAt: T0,
    updatedBy: ALICE,
    ...overrides,
  };
}

function item(overrides = {}) {
  return {
    name: 'mleko',
    categoryId: 'nabial',
    sortKey: 1,
    checked: false,
    createdAt: T0,
    createdBy: ALICE,
    updatedAt: T0,
    updatedBy: ALICE,
    changedAt: TIMESTAMP,
    ...overrides,
  };
}

/** The whole-list upload a device makes when a list first goes to RTDB. */
function upload(uid = ALICE, listId = LIST) {
  return {
    [`lists/${listId}/meta`]: meta({ ownerUid: uid, updatedBy: uid }),
    [`lists/${listId}/categories/nabial`]: { name: 'Nabiał i jaja', builtin: true, updatedAt: T0, updatedBy: uid },
    [`lists/${listId}/items/${ITEM}`]: item({ createdBy: uid, updatedBy: uid }),
    [`userLists/${uid}/${listId}`]: 'owner',
  };
}

/** An existing list of Alice's, with one item, as the server holds it. */
async function seedList() {
  await seed(`lists/${LIST}`, {
    meta: meta(),
    categories: { nabial: { name: 'Nabiał i jaja', builtin: true, updatedAt: T0, updatedBy: ALICE } },
    items: { [ITEM]: { ...item(), changedAt: T0 } },
  });
  await seed(`userLists/${ALICE}/${LIST}`, 'owner');
}

const itemPath = (field) => `lists/${LIST}/items/${ITEM}${field ? `/${field}` : ''}`;

/** What an `item.put` op writes: the content group, its stamp and changedAt. */
function contentWrite(at, content = {}, by = ALICE) {
  const c = { name: 'mleko', categoryId: 'nabial', sortKey: 1, manualKey: null, quantity: null, unit: null, note: null, photoAt: null, ...content };
  const paths = {};
  for (const [k, v] of Object.entries(c)) paths[itemPath(k)] = v;
  paths[itemPath('updatedAt')] = at;
  paths[itemPath('updatedBy')] = by;
  paths[itemPath('createdAt')] = T0;
  paths[itemPath('createdBy')] = ALICE;
  paths[itemPath('changedAt')] = TIMESTAMP;
  return paths;
}

function checkWrite(at, checked = true, by = ALICE) {
  return {
    [itemPath('checked')]: checked,
    [itemPath('checkedAt')]: at,
    [itemPath('checkedBy')]: by,
    [itemPath('changedAt')]: TIMESTAMP,
  };
}

describe('users and emailIndex', () => {
  test('a user writes and reads their own profile', async () => {
    await assertSucceeds(db(ALICE).ref(`users/${ALICE}`).set({ name: 'Alice', email: 'a@example.com', updatedAt: T0 }));
    await assertSucceeds(db(ALICE).ref(`users/${ALICE}`).get());
  });

  test("no one reads or writes someone else's profile", async () => {
    await seed(`users/${ALICE}`, { name: 'Alice', updatedAt: T0 });
    await assertFails(db(BOB).ref(`users/${ALICE}`).get());
    await assertFails(db(BOB).ref(`users/${ALICE}/name`).set('Bob'));
    await assertFails(anonymous().ref(`users/${ALICE}`).get());
  });

  test('unknown profile fields are rejected', async () => {
    await assertFails(db(ALICE).ref(`users/${ALICE}`).set({ name: 'Alice', admin: true }));
  });

  test("a member's name, e-mail and photo are readable by any signed-in user, the rest is not", async () => {
    await seed(`users/${ALICE}`, { name: 'Alice', email: 'alice@example.com', updatedAt: T0, prefs: { defaultOrder: { value: 'x', updatedAt: T0 } } });
    await assertSucceeds(db(BOB).ref(`users/${ALICE}/name`).get());
    await assertSucceeds(db(BOB).ref(`users/${ALICE}/email`).get());
    await assertFails(db(BOB).ref(`users/${ALICE}/prefs`).get());
    await assertFails(db(BOB).ref('users').get());
    await assertFails(anonymous().ref(`users/${ALICE}/name`).get());
  });

  test('an emailIndex entry is keyed by the writer’s own verified address (decision 63)', async () => {
    const alice = withEmail(ALICE, 'Alice.Smith@example.com');
    await assertSucceeds(alice.ref('emailIndex/alice,smith@example,com').set(ALICE));
    // Not someone else's address, not someone else's uid, not an unverified address.
    await assertFails(alice.ref('emailIndex/bob@example,com').set(ALICE));
    await assertFails(alice.ref('emailIndex/alice,smith@example,com').set(BOB));
    await assertFails(withEmail(BOB, 'alice.smith@example.com', false).ref('emailIndex/alice,smith@example,com').set(BOB));
    await assertFails(withEmail(BOB, 'bob@example.com').ref('emailIndex/alice,smith@example,com').set(BOB));
    // One key is readable by a signed-in user; the index is not listable.
    await assertSucceeds(db(BOB).ref('emailIndex/alice,smith@example,com').get());
    await assertFails(db(BOB).ref('emailIndex').get());
    await assertFails(anonymous().ref('emailIndex/alice,smith@example,com').get());
    await assertSucceeds(alice.ref('emailIndex/alice,smith@example,com').remove());
  });
});

describe('prefs', () => {
  const prefs = `users/${ALICE}/prefs`;

  test('the default order is last-writer-wins by updatedAt', async () => {
    await assertSucceeds(db(ALICE).ref(`${prefs}/defaultOrder`).set({ value: 'nabial,warzywa', updatedAt: T0 + 10 }));
    await assertFails(db(ALICE).ref(`${prefs}/defaultOrder`).set({ value: 'warzywa,nabial', updatedAt: T0 + 5 }));
    await assertSucceeds(db(ALICE).ref(`${prefs}/defaultOrder`).set({ value: 'warzywa,nabial', updatedAt: T0 + 20 }));
    await assertFails(db(BOB).ref(`${prefs}/defaultOrder`).set({ value: 'x', updatedAt: T0 + 30 }));
  });

  test('a category memory entry needs a server changedAt and a newer at', async () => {
    const entry = `${prefs}/categoryMemory/mleko`;
    await assertSucceeds(db(ALICE).ref(entry).set({ name: 'mleko', categoryId: 'nabial', at: T0, changedAt: TIMESTAMP }));
    await assertFails(db(ALICE).ref(entry).set({ name: 'mleko', categoryId: 'inne', at: T0 - 1, changedAt: TIMESTAMP }));
    await assertFails(db(ALICE).ref(entry).set({ name: 'mleko', categoryId: 'inne', at: T0 + 1, changedAt: T0 }));
    await assertSucceeds(
      db(ALICE).ref(`${prefs}/categoryMemory`).orderByChild('changedAt').startAt(0).get(),
    );
  });

  // „Moje produkty" (PLAN.md Phase 8b, task 1; STATE.md decisions 88 and 89).
  test('an own product needs a server changedAt and a newer at, and may be a tombstone', async () => {
    const entry = `${prefs}/products/chleb wiejski`;
    await assertSucceeds(
      db(ALICE).ref(entry).set({ name: 'chleb wiejski', categoryId: 'pieczywo', at: T0, changedAt: TIMESTAMP }),
    );
    // An older write loses, the server's clock is not optional, and no other field may ride along.
    await assertFails(db(ALICE).ref(entry).set({ name: 'chleb wiejski', categoryId: 'inne', at: T0 - 1, changedAt: TIMESTAMP }));
    await assertFails(db(ALICE).ref(entry).set({ name: 'chleb wiejski', categoryId: 'inne', at: T0 + 1, changedAt: T0 }));
    await assertFails(db(ALICE).ref(entry).set({ name: '', categoryId: 'pieczywo', at: T0 + 1, changedAt: TIMESTAMP }));
    await assertFails(
      db(ALICE).ref(entry).set({ name: 'chleb wiejski', categoryId: 'pieczywo', at: T0 + 1, changedAt: TIMESTAMP, uid: BOB }),
    );
    // A delete travels as a tombstone, and the catch-up reads the lot by changedAt.
    await assertSucceeds(
      db(ALICE).ref(entry).set({ name: 'chleb wiejski', categoryId: 'pieczywo', at: T0 + 2, deleted: true, changedAt: TIMESTAMP }),
    );
    await assertSucceeds(db(ALICE).ref(`${prefs}/products`).orderByChild('changedAt').startAt(0).get());
  });

  test('nobody else reads or writes another user’s products', async () => {
    const entry = `${prefs}/products/chleb wiejski`;
    await seed(entry, { name: 'chleb wiejski', categoryId: 'pieczywo', at: T0, changedAt: T0 });
    await assertFails(db(BOB).ref(entry).get());
    await assertFails(db(BOB).ref(`${prefs}/products`).get());
    await assertFails(db(BOB).ref(entry).set({ name: 'ser zolty', categoryId: 'nabial', at: T0 + 1, changedAt: TIMESTAMP }));
    await assertFails(db(BOB).ref(entry).remove());
    await assertFails(anonymous().ref(entry).get());
    await assertSucceeds(db(ALICE).ref(entry).get());
  });

  test('unknown prefs are rejected', async () => {
    await assertFails(db(ALICE).ref(`${prefs}/theme`).set('dark'));
  });
});

describe('creating a list', () => {
  test('the owner uploads a whole list with its userLists entry in one update', async () => {
    await assertSucceeds(db(ALICE).ref().update(upload()));
    await assertSucceeds(db(ALICE).ref(`lists/${LIST}`).get());
    await assertSucceeds(db(ALICE).ref(`userLists/${ALICE}`).get());
  });

  test('a list cannot be created in someone else’s name', async () => {
    await assertFails(db(BOB).ref().update({ [`lists/${LIST}/meta`]: meta({ ownerUid: ALICE, updatedBy: BOB }) }));
  });

  test('signed out, nothing is written or read', async () => {
    await assertFails(anonymous().ref().update(upload()));
    await seedList();
    await assertFails(anonymous().ref(`lists/${LIST}`).get());
  });

  test('a meta without its required fields is rejected', async () => {
    await assertFails(db(ALICE).ref(`lists/${LIST}/meta`).set({ name: 'Zakupy', ownerUid: ALICE }));
  });
});

describe("someone else's list", () => {
  beforeEach(seedList);

  test('is neither readable nor writable', async () => {
    await assertFails(db(BOB).ref(`lists/${LIST}`).get());
    await assertFails(db(BOB).ref(`lists/${LIST}/items`).orderByChild('changedAt').startAt(0).get());
    await assertFails(db(BOB).ref().update(contentWrite(T0 + 10, {}, BOB)));
    await assertFails(db(BOB).ref(`lists/${LIST}/meta/name`).set('Moja'));
    await assertFails(db(BOB).ref(`lists/${LIST}`).remove());
  });

  test('cannot be claimed in userLists', async () => {
    await assertFails(db(BOB).ref(`userLists/${BOB}/${LIST}`).set('owner'));
  });

  test("and nobody reads another user's userLists", async () => {
    await assertFails(db(BOB).ref(`userLists/${ALICE}`).get());
  });
});

describe('items: the newer write wins on the server', () => {
  beforeEach(seedList);

  test('a newer content write is accepted', async () => {
    await assertSucceeds(db(ALICE).ref().update(contentWrite(T0 + 10, { name: 'mleko 2%', quantity: 2, unit: 'l' })));
    const stored = (await db(ALICE).ref(itemPath()).get()).val();
    if (stored.name !== 'mleko 2%' || stored.quantity !== 2) throw new Error(`unexpected ${JSON.stringify(stored)}`);
  });

  test('a content write carrying an older updatedAt than the stored one is rejected', async () => {
    await assertSucceeds(db(ALICE).ref().update(contentWrite(T0 + 10, { name: 'mleko 2%' })));
    await assertFails(db(ALICE).ref().update(contentWrite(T0 + 5, { name: 'mleko 3,2%' })));
    const stored = (await db(ALICE).ref(itemPath()).get()).val();
    if (stored.name !== 'mleko 2%') throw new Error(`the older write got through: ${stored.name}`);
  });

  test('sending the same write again is harmless', async () => {
    await assertSucceeds(db(ALICE).ref().update(contentWrite(T0 + 10, { name: 'mleko 2%' })));
    await assertSucceeds(db(ALICE).ref().update(contentWrite(T0 + 10, { name: 'mleko 2%' })));
  });

  test('changed content without a newer stamp is rejected', async () => {
    await assertFails(db(ALICE).ref().update(contentWrite(T0, { name: 'kefir' })));
    await assertFails(db(ALICE).ref(itemPath('name')).set('kefir'));
  });

  test('the tick has its own stamp, independent of the content', async () => {
    await assertSucceeds(db(ALICE).ref().update(checkWrite(T0 + 20)));
    // An older content write is still older, whatever the tick did.
    await assertSucceeds(db(ALICE).ref().update(contentWrite(T0 + 5, { note: 'bez laktozy' })));
    await assertFails(db(ALICE).ref().update(checkWrite(T0 + 15, false)));
    await assertSucceeds(db(ALICE).ref().update(checkWrite(T0 + 30, false)));
  });

  test('a tick without its stamp is rejected', async () => {
    await assertFails(db(ALICE).ref().update({ [itemPath('checked')]: true, [itemPath('changedAt')]: TIMESTAMP }));
  });

  test('every write must carry the server changedAt', async () => {
    const paths = contentWrite(T0 + 10, { name: 'kefir' });
    delete paths[itemPath('changedAt')];
    await assertFails(db(ALICE).ref().update(paths));
    await assertFails(db(ALICE).ref().update({ ...paths, [itemPath('changedAt')]: T0 + 10 }));
  });

  test('a tombstone is final: no content, no tick after it', async () => {
    await assertSucceeds(db(ALICE).ref().update({ [itemPath('deletedAt')]: T0 + 10, [itemPath('changedAt')]: TIMESTAMP }));
    await assertFails(db(ALICE).ref().update(contentWrite(T0 + 20, { name: 'kefir' })));
    await assertFails(db(ALICE).ref().update(checkWrite(T0 + 20)));
    await assertFails(db(ALICE).ref().update({ [itemPath('deletedAt')]: null, [itemPath('changedAt')]: TIMESTAMP }));
    await assertFails(db(ALICE).ref().update({ [itemPath('deletedAt')]: T0 + 5, [itemPath('changedAt')]: TIMESTAMP }));
  });

  test('createdAt and createdBy are written once', async () => {
    await assertFails(db(ALICE).ref().update({ ...contentWrite(T0 + 10), [itemPath('createdAt')]: T0 + 10 }));
  });

  test('the actor is the writer', async () => {
    await assertFails(db(ALICE).ref().update(contentWrite(T0 + 10, {}, BOB)));
  });

  test('shape: unknown fields, wrong types and long text are rejected', async () => {
    await assertFails(db(ALICE).ref().update({ ...contentWrite(T0 + 10), [itemPath('price')]: 3 }));
    await assertFails(db(ALICE).ref().update(contentWrite(T0 + 10, { quantity: 'dwa' })));
    await assertFails(db(ALICE).ref().update(contentWrite(T0 + 10, { name: 'x'.repeat(201) })));
    await assertFails(db(ALICE).ref().update(contentWrite(T0 + 10, { note: 'x'.repeat(2001) })));
    await assertFails(db(ALICE).ref().update(contentWrite(T0 + 10, { name: '' })));
  });

  test('a device catches up with items by changedAt', async () => {
    await assertSucceeds(db(ALICE).ref(`lists/${LIST}/items`).orderByChild('changedAt').startAt(T0).get());
  });
});

describe('meta and categories', () => {
  beforeEach(seedList);

  test('a rename needs a newer updatedAt', async () => {
    await assertSucceeds(db(ALICE).ref(`lists/${LIST}/meta`).update({ name: 'Biedronka', updatedAt: T0 + 10 }));
    await assertFails(db(ALICE).ref(`lists/${LIST}/meta`).update({ name: 'Lidl', updatedAt: T0 + 5 }));
    await assertFails(db(ALICE).ref(`lists/${LIST}/meta`).update({ name: 'Lidl' }));
  });

  test('the owner never changes', async () => {
    await assertFails(db(ALICE).ref(`lists/${LIST}/meta/ownerUid`).set(BOB));
  });

  test('„Wyczyść kupione" only moves forward', async () => {
    await assertSucceeds(db(ALICE).ref(`lists/${LIST}/meta/clearedAt`).set(T0 + 10));
    await assertFails(db(ALICE).ref(`lists/${LIST}/meta/clearedAt`).set(T0 + 5));
    await assertFails(db(ALICE).ref(`lists/${LIST}/meta/clearedAt`).remove());
  });

  test('a category put needs a newer updatedAt; its tombstone keeps where items went', async () => {
    const cat = `lists/${LIST}/categories/nabial`;
    await assertSucceeds(db(ALICE).ref(cat).update({ name: 'Nabiał', updatedAt: T0 + 10 }));
    await assertFails(db(ALICE).ref(cat).update({ name: 'Jaja', updatedAt: T0 + 5 }));
    await assertSucceeds(db(ALICE).ref(cat).update({ deletedAt: T0 + 20, moveItemsTo: 'inne' }));
    await assertFails(db(ALICE).ref(cat).update({ moveItemsTo: 'warzywa' }));
    await assertFails(db(ALICE).ref(cat).update({ deletedAt: null }));
  });

  test('deleting a list marks the meta and removes items, categories and photos in one update', async () => {
    await seed(`photos/${LIST}/${ITEM}`, { webp: 'UklGRg==', w: 1, h: 1, by: ALICE, at: T0 });
    await assertSucceeds(
      db(ALICE).ref().update({
        [`lists/${LIST}/meta/deletedAt`]: T0 + 10,
        [`lists/${LIST}/items`]: null,
        [`lists/${LIST}/categories`]: null,
        [`photos/${LIST}`]: null,
      }),
    );
    // Nothing is written under a deleted list, and it is not renamed either.
    await assertFails(db(ALICE).ref().update(contentWrite(T0 + 20, { name: 'kefir' })));
    await assertFails(db(ALICE).ref(`lists/${LIST}/meta`).update({ name: 'Nowa', updatedAt: T0 + 30 }));
    // Thirty days later the owner removes what is left.
    await assertSucceeds(
      db(ALICE).ref().update({
        [`lists/${LIST}`]: null,
        [`photos/${LIST}`]: null,
        [`userLists/${ALICE}/${LIST}`]: null,
      }),
    );
  });

  test('only the owner removes a list', async () => {
    await assertFails(db(BOB).ref().update({ [`lists/${LIST}/items`]: null }));
    await assertFails(db(BOB).ref(`lists/${LIST}/categories`).remove());
  });

  test('unknown children of a list are rejected', async () => {
    await assertFails(db(ALICE).ref(`lists/${LIST}/extra`).set(true));
  });
});

// --- Phase 5: sharing ------------------------------------------------------------------------

const CAROL = 'carol-uid';
const DAVE = 'dave-uid';
const TOKEN = 'AbCdEfGhIjKlMnOpQrStUv'; // 22 URL-safe characters: 128 bits
const OTHER_TOKEN = 'ZyXwVuTsRqPoNmLkJiHgFe';
const DAY = 24 * 60 * 60 * 1000;

/** Alice's list shared with Bob as an editor and Carol as a viewer. */
async function seedShared() {
  await seedList();
  await seed(`lists/${LIST}/members`, {
    [ALICE]: { role: 'owner', since: T0 },
    [BOB]: { role: 'editor', since: T0 },
    [CAROL]: { role: 'viewer', since: T0 },
  });
  await seed(`userLists/${BOB}/${LIST}`, 'editor');
  await seed(`userLists/${CAROL}/${LIST}`, 'viewer');
}

function invite(overrides = {}) {
  return {
    listId: LIST,
    role: 'editor',
    by: ALICE,
    expiresAt: Date.now() + 7 * DAY,
    listName: 'Zakupy',
    byName: 'Alice',
    ...overrides,
  };
}

/** What accepting an invite writes: the member node with the token, and the userLists entry. */
function accept(uid, role = 'editor', token = TOKEN) {
  return {
    [`lists/${LIST}/members/${uid}`]: { role, since: TIMESTAMP, invite: token },
    [`userLists/${uid}/${LIST}`]: role,
  };
}

describe('roles', () => {
  beforeEach(seedShared);

  test('a non-member reads nothing of a shared list', async () => {
    const dave = db(DAVE);
    await assertFails(dave.ref(`lists/${LIST}`).get());
    await assertFails(dave.ref(`lists/${LIST}/items`).orderByChild('changedAt').startAt(0).get());
    await assertFails(dave.ref(`lists/${LIST}/members`).get());
    await assertFails(dave.ref(`photos/${LIST}`).get());
  });

  test('every member reads the whole list and its photos', async () => {
    for (const uid of [ALICE, BOB, CAROL]) {
      await assertSucceeds(db(uid).ref(`lists/${LIST}`).get());
      await assertSucceeds(db(uid).ref(`lists/${LIST}/items`).orderByChild('changedAt').startAt(0).get());
      await assertSucceeds(db(uid).ref(`photos/${LIST}`).get());
    }
  });

  test('an editor adds, edits, ticks and deletes items', async () => {
    const bob = db(BOB);
    await assertSucceeds(bob.ref().update(contentWrite(T0 + 10, { name: 'kefir' }, BOB)));
    await assertSucceeds(bob.ref().update(checkWrite(T0 + 20, true, BOB)));
    const fresh = `lists/${LIST}/items/item-2`;
    await assertSucceeds(bob.ref(fresh).set(item({ name: 'chleb', createdBy: BOB, updatedBy: BOB })));
    await assertSucceeds(bob.ref().update({ [`${fresh}/deletedAt`]: T0 + 30, [`${fresh}/changedAt`]: TIMESTAMP }));
  });

  test('an editor adds a category, renames the list and clears "Kupione"', async () => {
    const bob = db(BOB);
    await assertSucceeds(
      bob.ref(`lists/${LIST}/categories/own-1`).set({ name: 'Chemia', builtin: false, updatedAt: T0 + 10, updatedBy: BOB }),
    );
    await assertSucceeds(
      bob.ref(`lists/${LIST}/meta`).update({ name: 'Biedronka', categoryOrder: ['nabial', 'own-1'], updatedAt: T0 + 10, updatedBy: BOB }),
    );
    await assertSucceeds(bob.ref(`lists/${LIST}/meta/clearedAt`).set(T0 + 20));
  });

  test('an editor neither deletes the list nor removes nodes', async () => {
    const bob = db(BOB);
    await assertFails(bob.ref(`lists/${LIST}/meta/deletedAt`).set(T0 + 10));
    await assertFails(bob.ref(`lists/${LIST}/meta`).remove());
    await assertFails(bob.ref(itemPath()).remove());
    await assertFails(bob.ref(`lists/${LIST}/items`).remove());
    await assertFails(bob.ref(`lists/${LIST}`).remove());
  });

  test('a viewer cannot touch anything, not even a tick', async () => {
    const carol = db(CAROL);
    await assertFails(carol.ref().update(checkWrite(T0 + 10, true, CAROL)));
    await assertFails(carol.ref().update(contentWrite(T0 + 10, { name: 'kefir' }, CAROL)));
    await assertFails(carol.ref(`lists/${LIST}/items/item-2`).set(item({ createdBy: CAROL, updatedBy: CAROL })));
    await assertFails(carol.ref(`lists/${LIST}/meta/clearedAt`).set(T0 + 10));
    await assertFails(
      carol.ref(`lists/${LIST}/categories/own-1`).set({ name: 'X', builtin: false, updatedAt: T0 + 10, updatedBy: CAROL }),
    );
  });

  test("an older op from an editor is refused like anyone's", async () => {
    await assertSucceeds(db(ALICE).ref().update(contentWrite(T0 + 10, { name: 'mleko 2%' })));
    await assertFails(db(BOB).ref().update(contentWrite(T0 + 5, { name: 'mleko 3,2%' }, BOB)));
    await assertSucceeds(db(ALICE).ref().update(checkWrite(T0 + 20)));
    await assertFails(db(BOB).ref().update(checkWrite(T0 + 15, false, BOB)));
  });

  test('an editor cannot write as someone else', async () => {
    await assertFails(db(BOB).ref().update(contentWrite(T0 + 10, { name: 'kefir' }, ALICE)));
    await assertFails(db(BOB).ref().update(checkWrite(T0 + 10, true, ALICE)));
  });

  test('the manual order is content: it needs a newer stamp', async () => {
    await assertSucceeds(db(BOB).ref().update(contentWrite(T0 + 10, { manualKey: 3 }, BOB)));
    await assertFails(db(BOB).ref(itemPath('manualKey')).set(4));
    await assertFails(db(BOB).ref().update(contentWrite(T0 + 10, { manualKey: 4 }, BOB)));
    await assertFails(db(BOB).ref().update(contentWrite(T0 + 20, { manualKey: 'first' }, BOB)));
  });
});

describe('members', () => {
  beforeEach(seedShared);

  test('only the owner writes members', async () => {
    await assertFails(db(BOB).ref(`lists/${LIST}/members/${CAROL}/role`).set('editor'));
    await assertFails(db(BOB).ref(`lists/${LIST}/members/${DAVE}`).set({ role: 'editor', since: T0 }));
    await assertFails(db(CAROL).ref(`lists/${LIST}/members/${CAROL}`).set({ role: 'editor', since: T0 }));
    await assertFails(db(BOB).ref(`lists/${LIST}/members/${CAROL}`).remove());
    await assertFails(db(BOB).ref(`lists/${LIST}/members`).remove());
  });

  test('the owner adds by e-mail, changes a role and removes, with the userLists entries', async () => {
    const alice = db(ALICE);
    await assertSucceeds(
      alice.ref().update({
        [`lists/${LIST}/members/${DAVE}`]: { role: 'viewer', since: TIMESTAMP },
        [`userLists/${DAVE}/${LIST}`]: 'viewer',
      }),
    );
    await assertSucceeds(
      alice.ref().update({ [`lists/${LIST}/members/${CAROL}/role`]: 'editor', [`userLists/${CAROL}/${LIST}`]: 'editor' }),
    );
    await assertSucceeds(
      alice.ref().update({
        [`lists/${LIST}/members/${BOB}`]: null,
        [`userLists/${BOB}/${LIST}`]: null,
        [`lists/${LIST}/presence/${BOB}`]: null,
      }),
    );
    // A removed member reads nothing any more.
    await assertFails(db(BOB).ref(`lists/${LIST}`).get());
  });

  test('a userLists entry must match the role in the members node', async () => {
    await assertFails(db(ALICE).ref(`userLists/${BOB}/${LIST}`).set('viewer'));
    await assertFails(db(ALICE).ref(`userLists/${BOB}/${LIST}`).set('owner'));
    await assertFails(db(BOB).ref(`userLists/${BOB}/${LIST}`).set('owner'));
    await assertFails(db(CAROL).ref(`userLists/${CAROL}/${LIST}`).set('editor'));
    await assertFails(db(BOB).ref(`userLists/${CAROL}/${LIST}`).remove());
  });

  test('the owner stays the owner, and nobody else can be one', async () => {
    await assertFails(db(ALICE).ref(`lists/${LIST}/members/${ALICE}/role`).set('editor'));
    await assertFails(db(ALICE).ref(`lists/${LIST}/members/${BOB}/role`).set('owner'));
    await assertFails(db(ALICE).ref(`lists/${LIST}/members/${BOB}/role`).set('admin'));
  });

  test('a member leaves on their own', async () => {
    await assertSucceeds(
      db(CAROL).ref().update({ [`lists/${LIST}/members/${CAROL}`]: null, [`userLists/${CAROL}/${LIST}`]: null }),
    );
    await assertFails(db(CAROL).ref(`lists/${LIST}`).get());
  });

  test('"Uczyń prywatną" removes every member at once', async () => {
    await assertSucceeds(
      db(ALICE).ref().update({
        [`lists/${LIST}/members`]: null,
        [`userLists/${BOB}/${LIST}`]: null,
        [`userLists/${CAROL}/${LIST}`]: null,
      }),
    );
    await assertFails(db(BOB).ref(`lists/${LIST}`).get());
    await assertSucceeds(db(ALICE).ref(`lists/${LIST}`).get());
  });

  test('"Udostępnij" makes a private list shared with the owner as its first member', async () => {
    await seed(`lists/${LIST}/members`, null);
    await assertSucceeds(db(ALICE).ref(`lists/${LIST}/members/${ALICE}`).set({ role: 'owner', since: TIMESTAMP }));
  });
});

describe('invites', () => {
  beforeEach(seedList);

  test('the owner creates an invite; nobody else can', async () => {
    await assertSucceeds(db(ALICE).ref(`invites/${TOKEN}`).set(invite()));
    await assertFails(db(BOB).ref(`invites/${OTHER_TOKEN}`).set(invite({ by: BOB })));
    await assertFails(db(BOB).ref(`invites/${OTHER_TOKEN}`).set(invite()));
    await assertFails(db(BOB).ref(`invites/${TOKEN}`).remove());
  });

  test('an invite has a proper token, role and lifetime', async () => {
    await assertFails(db(ALICE).ref('invites/short').set(invite()));
    await assertFails(db(ALICE).ref(`invites/${TOKEN}`).set(invite({ role: 'owner' })));
    await assertFails(db(ALICE).ref(`invites/${TOKEN}`).set(invite({ expiresAt: Date.now() + 30 * DAY })));
    await assertFails(db(ALICE).ref(`invites/${TOKEN}`).set({ ...invite(), admin: true }));
  });

  test('a signed-in user reads one invite by its token, never the index', async () => {
    await seed(`invites/${TOKEN}`, invite());
    await assertSucceeds(db(BOB).ref(`invites/${TOKEN}`).get());
    await assertFails(db(BOB).ref('invites').get());
    await assertFails(anonymous().ref(`invites/${TOKEN}`).get());
  });

  test('accepting is allowed once, and then the whole list is readable', async () => {
    await seed(`invites/${TOKEN}`, invite());
    await assertFails(db(BOB).ref(`lists/${LIST}`).get());
    await assertSucceeds(db(BOB).ref().update(accept(BOB)));
    await assertSucceeds(db(BOB).ref(`lists/${LIST}`).get());
    await assertSucceeds(db(BOB).ref(`photos/${LIST}`).get());
    // The same person cannot accept again (to change their role, say).
    await assertFails(db(BOB).ref().update(accept(BOB)));
    // Someone else may use the same link, once.
    await assertSucceeds(db(CAROL).ref().update(accept(CAROL)));
  });

  test('an expired invite is refused', async () => {
    await seed(`invites/${TOKEN}`, invite({ expiresAt: Date.now() - 1000 }));
    await assertFails(db(BOB).ref().update(accept(BOB)));
  });

  test('an invite gives only its own role, for its own list', async () => {
    await seed(`invites/${TOKEN}`, invite({ role: 'viewer' }));
    await assertFails(db(BOB).ref().update(accept(BOB, 'editor')));
    await assertFails(db(BOB).ref().update(accept(BOB, 'owner')));
    await seed('lists/list-2', { meta: meta() });
    await assertFails(
      db(BOB).ref().update({ [`lists/list-2/members/${BOB}`]: { role: 'viewer', since: TIMESTAMP, invite: TOKEN } }),
    );
    await assertSucceeds(db(BOB).ref().update(accept(BOB, 'viewer')));
  });

  test("no invite, a made-up token, or someone else's member node: refused", async () => {
    await assertFails(db(BOB).ref(`lists/${LIST}/members/${BOB}`).set({ role: 'editor', since: T0 }));
    await assertFails(db(BOB).ref().update(accept(BOB, 'editor', OTHER_TOKEN)));
    await seed(`invites/${TOKEN}`, invite());
    await assertFails(db(BOB).ref(`lists/${LIST}/members/${CAROL}`).set({ role: 'editor', since: TIMESTAMP, invite: TOKEN }));
  });

  test('the owner removes an expired invite, and keeps an index of their own', async () => {
    await seed(`invites/${TOKEN}`, invite({ expiresAt: Date.now() - 1000 }));
    await assertSucceeds(db(ALICE).ref(`users/${ALICE}/invites/${TOKEN}`).set({ listId: LIST, expiresAt: T0 }));
    await assertFails(db(BOB).ref(`users/${ALICE}/invites`).get());
    await assertSucceeds(
      db(ALICE).ref().update({ [`invites/${TOKEN}`]: null, [`users/${ALICE}/invites/${TOKEN}`]: null }),
    );
  });
});

describe('presence', () => {
  beforeEach(seedShared);

  test('a member marks themselves present with the server time, and goes away', async () => {
    await assertSucceeds(db(CAROL).ref(`lists/${LIST}/presence/${CAROL}`).set(TIMESTAMP));
    await assertSucceeds(db(BOB).ref(`lists/${LIST}/presence`).get());
    await assertFails(db(CAROL).ref(`lists/${LIST}/presence/${CAROL}`).set(T0));
    await assertSucceeds(db(CAROL).ref(`lists/${LIST}/presence/${CAROL}`).remove());
  });

  test("nobody is present in someone else's name or in a list they are not in", async () => {
    await assertFails(db(BOB).ref(`lists/${LIST}/presence/${CAROL}`).set(TIMESTAMP));
    await assertFails(db(DAVE).ref(`lists/${LIST}/presence/${DAVE}`).set(TIMESTAMP));
  });
});

describe('cleanup', () => {
  beforeEach(seedShared);

  test('the owner removes old tombstones and their photos; an editor removes only a photo', async () => {
    await assertSucceeds(
      db(ALICE).ref().update({
        [itemPath()]: null,
        [`photos/${LIST}/${ITEM}`]: null,
        [`lists/${LIST}/categories/nabial`]: null,
      }),
    );
    await assertSucceeds(db(BOB).ref(`photos/${LIST}/${ITEM}`).remove());
    await assertFails(db(CAROL).ref(`photos/${LIST}/${ITEM}`).remove());
    await assertFails(db(BOB).ref(`photos/${LIST}/${ITEM}`).set({ webp: 'x' }));
  });

  test("a deleted list is removed whole, with every member's userLists entry", async () => {
    await seed(`lists/${LIST}/meta/deletedAt`, T0 + 10);
    await assertSucceeds(
      db(ALICE).ref().update({
        [`lists/${LIST}`]: null,
        [`userLists/${ALICE}/${LIST}`]: null,
        [`userLists/${BOB}/${LIST}`]: null,
        [`userLists/${CAROL}/${LIST}`]: null,
      }),
    );
  });
});

// --- Phase 6: photos (STATE.md decisions 71 and 72) -------------------------------------------

/** A photo node as `PhotoSync` writes it; `webp` is base64. */
function photo(overrides = {}) {
  return { webp: 'UklGRiQAAABXRUJQVlA4IBgAAAAwAQCdASoBAAEAAwA0JaQAA3AA/vuUAAA=', w: 800, h: 600, by: ALICE, at: T0 + 10, ...overrides };
}

const photoPath = (item = ITEM) => `photos/${LIST}/${item}`;

describe('photos', () => {
  beforeEach(seedShared);

  test('the owner and an editor put a photo; a viewer and a stranger cannot', async () => {
    await assertSucceeds(db(ALICE).ref(photoPath()).set(photo()));
    await assertSucceeds(db(BOB).ref(photoPath()).set(photo({ by: BOB, at: T0 + 20 })));
    await assertFails(db(CAROL).ref(photoPath()).set(photo({ by: CAROL, at: T0 + 30 })));
    await assertFails(db(DAVE).ref(photoPath()).set(photo({ by: DAVE, at: T0 + 30 })));
  });

  test('members read a photo; a stranger does not', async () => {
    await seed(photoPath(), photo());
    for (const uid of [ALICE, BOB, CAROL]) await assertSucceeds(db(uid).ref(photoPath()).get());
    await assertFails(db(DAVE).ref(photoPath()).get());
  });

  test('a photo is capped at 110 000 base64 characters and 800 px', async () => {
    await assertSucceeds(db(ALICE).ref(photoPath()).set(photo({ webp: 'A'.repeat(110000) })));
    await assertFails(db(ALICE).ref(photoPath()).set(photo({ webp: 'A'.repeat(110001), at: T0 + 20 })));
    await assertFails(db(ALICE).ref(photoPath()).set(photo({ w: 801, at: T0 + 20 })));
    await assertFails(db(ALICE).ref(photoPath()).set(photo({ h: 0, at: T0 + 20 })));
    await assertFails(db(ALICE).ref(photoPath()).set(photo({ webp: '', at: T0 + 20 })));
  });

  test('a photo has exactly its five fields, and is signed by its writer', async () => {
    await assertFails(db(ALICE).ref(photoPath()).set({ ...photo(), exif: 'GPS' }));
    const { at, ...withoutAt } = photo();
    await assertFails(db(ALICE).ref(photoPath()).set(withoutAt));
    await assertFails(db(BOB).ref(photoPath()).set(photo({ by: ALICE })));
  });

  test('a replacement is newer; an older photo never overwrites a newer one', async () => {
    await assertSucceeds(db(ALICE).ref(photoPath()).set(photo({ at: T0 + 20 })));
    await assertFails(db(BOB).ref(photoPath()).set(photo({ by: BOB, at: T0 + 10 })));
    await assertSucceeds(db(BOB).ref(photoPath()).set(photo({ by: BOB, at: T0 + 30 })));
  });

  test('a photo belongs to a live item of a live list', async () => {
    await assertFails(db(ALICE).ref(photoPath('no-such-item')).set(photo()));
    await seed(`${itemPath()}/deletedAt`, T0 + 5);
    await assertFails(db(ALICE).ref(photoPath()).set(photo()));
  });

  test('the item and its photo can be written in one update', async () => {
    await assertSucceeds(
      db(ALICE).ref().update({ ...contentWrite(T0 + 10, { name: 'kefir', photoAt: T0 + 10 }), [photoPath()]: photo() }),
    );
  });

  test('nothing is written under a deleted list', async () => {
    await seed(`lists/${LIST}/meta/deletedAt`, T0 + 5);
    await assertFails(db(ALICE).ref(photoPath()).set(photo()));
  });

  test('an editor removes one photo, never all; the owner removes all', async () => {
    await seed(photoPath(), photo());
    await assertFails(db(BOB).ref(`photos/${LIST}`).remove());
    await assertFails(db(CAROL).ref(photoPath()).remove());
    await assertSucceeds(db(BOB).ref(photoPath()).remove());
    await seed(photoPath(), photo());
    await assertSucceeds(db(ALICE).ref(`photos/${LIST}`).remove());
  });
});

describe('the sort view preference (decision 67)', () => {
  test('is per list, last-writer-wins, and one of three', async () => {
    const path = `users/${ALICE}/prefs/listSort/${LIST}`;
    await assertSucceeds(db(ALICE).ref(path).set({ value: 'manual', updatedAt: T0 + 10 }));
    await assertFails(db(ALICE).ref(path).set({ value: 'alphabetical', updatedAt: T0 + 5 }));
    await assertFails(db(ALICE).ref(path).set({ value: 'random', updatedAt: T0 + 20 }));
    await assertFails(db(BOB).ref(path).set({ value: 'alphabetical', updatedAt: T0 + 20 }));
  });
});

// --- Phase 9: the push registration and „Usuń moje dane" (decisions 97 and 98) --------------

describe('fcmTokens', () => {
  const TOKEN_A = 'fake-registration-token-a';

  test('a user registers their own device, and nobody else does', async () => {
    await assertSucceeds(db(ALICE).ref(`fcmTokens/${ALICE}/${TOKEN_A}`).set({ at: TIMESTAMP }));
    await assertFails(db(BOB).ref(`fcmTokens/${ALICE}/${TOKEN_A}`).set({ at: TIMESTAMP }));
    await assertFails(anonymous().ref(`fcmTokens/${ALICE}/${TOKEN_A}`).set({ at: TIMESTAMP }));
  });

  test('nobody reads a token, not even its own user: the script reads as the owner', async () => {
    await seed(`fcmTokens/${ALICE}/${TOKEN_A}`, { at: T0 });
    await assertFails(db(ALICE).ref(`fcmTokens/${ALICE}`).get());
    await assertFails(db(ALICE).ref(`fcmTokens/${ALICE}/${TOKEN_A}`).get());
    await assertFails(db(BOB).ref(`fcmTokens/${ALICE}`).get());
  });

  test('the node is exactly { at: now }', async () => {
    const ref = db(ALICE).ref(`fcmTokens/${ALICE}/${TOKEN_A}`);
    await assertFails(ref.set({ at: T0 })); // a device's own clock is not the server's
    await assertFails(ref.set({ at: TIMESTAMP, listId: LIST })); // nothing else may ride along
    await assertFails(ref.set(TIMESTAMP)); // not a bare value
    await assertSucceeds(ref.set({ at: TIMESTAMP }));
  });

  test('a user removes their own registration at sign-out', async () => {
    await seed(`fcmTokens/${ALICE}/${TOKEN_A}`, { at: T0 });
    await assertFails(db(BOB).ref(`fcmTokens/${ALICE}/${TOKEN_A}`).remove());
    await assertSucceeds(db(ALICE).ref(`fcmTokens/${ALICE}/${TOKEN_A}`).remove());
    await seed(`fcmTokens/${ALICE}/${TOKEN_A}`, { at: T0 });
    await assertSucceeds(db(ALICE).ref(`fcmTokens/${ALICE}`).remove());
  });
});

describe('„Usuń moje dane" (decision 97)', () => {
  beforeEach(seedShared);

  test('the owner removes their whole list, its photos and everyone’s userLists entry', async () => {
    await seed(`photos/${LIST}/${ITEM}`, { webp: 'x', w: 1, h: 1, by: ALICE, at: T0 });
    await assertSucceeds(
      db(ALICE).ref().update({
        [`lists/${LIST}`]: null,
        [`photos/${LIST}`]: null,
        [`userLists/${ALICE}/${LIST}`]: null,
        [`userLists/${BOB}/${LIST}`]: null,
        [`userLists/${CAROL}/${LIST}`]: null,
      }),
    );
  });

  test('a member leaves everybody else’s lists, which stay theirs', async () => {
    await assertSucceeds(
      db(BOB).ref().update({
        [`lists/${LIST}/members/${BOB}`]: null,
        [`userLists/${BOB}/${LIST}`]: null,
        [`lists/${LIST}/presence/${BOB}`]: null,
      }),
    );
    // Alice's list is untouched: only Bob's membership went.
    await assertSucceeds(db(ALICE).ref(`lists/${LIST}/meta`).get());
    await assertFails(db(BOB).ref(`lists/${LIST}`).get());
  });

  test('the account’s own nodes go, and nobody else’s', async () => {
    await seed(`fcmTokens/${ALICE}/token-a`, { at: T0 });
    await seed(`users/${ALICE}`, { name: 'Alice', email: 'alice@example.test', updatedAt: T0 });
    await seed('emailIndex/alice@example,test', ALICE);
    const alice = withEmail(ALICE, 'alice@example.test');
    await assertSucceeds(
      alice.ref().update({
        [`userLists/${ALICE}/${LIST}`]: null,
        [`users/${ALICE}`]: null,
        [`fcmTokens/${ALICE}`]: null,
        'emailIndex/alice@example,test': null,
      }),
    );
    // Somebody else's index entry is not Alice's to remove.
    await seed('emailIndex/bob@example,test', BOB);
    await assertFails(alice.ref('emailIndex/bob@example,test').remove());
    await assertFails(alice.ref(`users/${BOB}`).remove());
    await assertFails(alice.ref(`fcmTokens/${BOB}`).remove());
  });
});
