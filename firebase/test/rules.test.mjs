// Realtime Database rules tests (PLAN.md Phase 4, task 3; STATE.md decision 56).
//
// Run with `npm test` in this directory: `firebase emulators:exec` starts the database emulator
// under a demo project id (no login, never the real project) and runs this file with node:test.
// Phase 4 covers a single user's own lists; Phase 5 adds members, roles and invites.

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
  const c = { name: 'mleko', categoryId: 'nabial', sortKey: 1, quantity: null, unit: null, note: null, photoAt: null, ...content };
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

  test('an emailIndex entry names its writer, and cannot be taken over', async () => {
    const hash = 'a'.repeat(64);
    await assertSucceeds(db(ALICE).ref(`emailIndex/${hash}`).set(ALICE));
    await assertFails(db(BOB).ref(`emailIndex/${hash}`).set(BOB));
    await assertFails(db(BOB).ref(`emailIndex/${'b'.repeat(64)}`).set(ALICE));
    await assertFails(db(ALICE).ref('emailIndex/not-a-hash').set(ALICE));
    await assertFails(db(ALICE).ref(`emailIndex/${hash}`).get());
    await assertSucceeds(db(ALICE).ref(`emailIndex/${hash}`).remove());
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

  test('deleting a list marks the meta and removes items and categories in one update', async () => {
    await assertSucceeds(
      db(ALICE).ref().update({
        [`lists/${LIST}/meta/deletedAt`]: T0 + 10,
        [`lists/${LIST}/items`]: null,
        [`lists/${LIST}/categories`]: null,
      }),
    );
    // Nothing is written under a deleted list, and it is not renamed either.
    await assertFails(db(ALICE).ref().update(contentWrite(T0 + 20, { name: 'kefir' })));
    await assertFails(db(ALICE).ref(`lists/${LIST}/meta`).update({ name: 'Nowa', updatedAt: T0 + 30 }));
    // Thirty days later the owner removes what is left.
    await assertSucceeds(
      db(ALICE).ref().update({ [`lists/${LIST}`]: null, [`userLists/${ALICE}/${LIST}`]: null }),
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
