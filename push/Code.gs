/**
 * Buy My Way — push sender (Google Apps Script web app).
 *
 * POST {idToken, listId, kind, added, checked, changed}
 *   → the Firebase ID token is verified with Auth REST
 *   → the caller must be a member of that list
 *   → one FCM v1 **data** message per other member's device token.
 *
 * The message carries a list id, three numbers and the caller's display name. No item name,
 * note or photo ever travels through FCM: the receiving phone reads what changed from the
 * database itself, under the rules (PLAN.md *Security*, STATE.md decision 95).
 *
 * Script properties (Project settings → Script properties; ids, not secrets):
 *   FIREBASE_API_KEY     the Web API key of the Firebase project
 *   FIREBASE_PROJECT_ID  buy-my-way-c3949
 *   FIREBASE_DB_URL      https://buy-my-way-c3949-default-rtdb.europe-west1.firebasedatabase.app
 *
 * No service-account key: the script's Cloud project *is* the Firebase project, so
 * ScriptApp.getOAuthToken() carries the firebase.database and firebase.messaging scopes
 * (appsscript.json). Reading with that token is an owner's read, which is how the script sees
 * `/fcmTokens` even though the rules let nobody read it (decision 98).
 *
 * Deployment, and why a manifest change needs a new deployment version: docs/DEPLOYMENT.md.
 */

/** At most this many pushes per list per minute (STATE.md decision 99). */
var MAX_PUSHES_PER_MINUTE = 20;

/** A guard on the numbers, so a bad caller cannot make a notification say anything absurd. */
var MAX_COUNT = 9999;

function doPost(e) {
  try {
    return handle_(e);
  } catch (err) {
    // A misconfiguration must never look like an HTML error page to the phone, and must say
    // which one it is: this is what the first live test of Phase 9 cost an evening over.
    return reply_({ ok: false, error: 'misconfigured', detail: String(err).slice(0, 200) });
  }
}

function handle_(e) {
  var started = Date.now();
  var props = PropertiesService.getScriptProperties();
  var body;
  try {
    body = JSON.parse(e.postData.contents);
  } catch (err) {
    return reply_({ ok: false, error: 'bad request' });
  }
  if (!props.getProperty('FIREBASE_DB_URL')) {
    return reply_({ ok: false, error: 'misconfigured', detail: 'FIREBASE_DB_URL script property is not set' });
  }

  var uid = verifyIdToken_(body.idToken, props.getProperty('FIREBASE_API_KEY'));
  if (!uid) return reply_({ ok: false, error: 'unauthenticated' });

  var listId = String(body.listId || '');
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(listId)) return reply_({ ok: false, error: 'bad request' });
  var kind = body.kind === 'shared' ? 'shared' : 'changes';

  // The caller must be on the list. The owner counts as a member: the app writes an owner
  // entry the moment a list is shared (RemoteWrites.share). A database that cannot be read at
  // all is reported as itself, never as „you are not a member": those two look identical from
  // the phone, and the first is a deployment fault while the second is a decision.
  var members = dbGet_(props, '/lists/' + listId + '/members');
  if (members === DB_UNREACHABLE) {
    return reply_({ ok: false, error: 'misconfigured', detail: 'cannot read the database: check the firebase.database scope and FIREBASE_DB_URL' });
  }
  if (!members || !members[uid]) return reply_({ ok: false, error: 'forbidden' });

  if (!allow_(listId)) return reply_({ ok: true, skipped: 'rate' });

  var counts = {
    added: count_(body.added),
    checked: count_(body.checked),
    changed: count_(body.changed),
  };
  var total = counts.added + counts.checked + counts.changed;
  if (kind === 'changes' && total === 0) return reply_({ ok: true, skipped: 'nothing' });

  var actorNode = dbGet_(props, '/users/' + uid + '/name');
  var actor = (!actorNode || actorNode === DB_UNREACHABLE) ? '' : actorNode;
  var data = {
    listId: listId,
    kind: kind,
    actor: String(actor).slice(0, 100),
    added: String(counts.added),
    checked: String(counts.checked),
    changed: String(counts.changed),
    count: String(total),
  };

  var sent = 0;
  var dropped = 0;
  for (var memberUid in members) {
    if (memberUid === uid) continue;
    var tokens = dbGet_(props, '/fcmTokens/' + memberUid, { shallow: true });
    if (!tokens || tokens === DB_UNREACHABLE) continue;
    for (var token in tokens) {
      var outcome = sendTo_(props, token, data);
      if (outcome === 'gone') {
        // FCM says this device is no longer registered (STATE.md decision 36).
        dbDelete_(props, '/fcmTokens/' + memberUid + '/' + token);
        dropped++;
      } else if (outcome === 'ok') {
        sent++;
      }
    }
  }
  return reply_({ ok: true, sent: sent, dropped: dropped, scriptMs: Date.now() - started });
}

/**
 * Health check: GET the deployment URL.
 *
 * It answers for the **deployment**, not for the editor, which is the whole point: a web app
 * runs with the manifest of its own version, so the editor can hold every scope while the
 * deployed code holds none. `selfTest` cannot see that; this can. It reports no secret — scope
 * names are in the public repository and `db` is one HTTP status.
 */
function doGet() {
  var props = PropertiesService.getScriptProperties();
  var answer = { ok: true };
  try {
    answer.missingScopes = missingScopes_();
    var url = props.getProperty('FIREBASE_DB_URL');
    answer.db = url ? dbStatus_(url) : 'FIREBASE_DB_URL not set';
  } catch (err) {
    answer.diagnostics = String(err).slice(0, 200);
  }
  return reply_(answer);
}

/** Which of the scopes the sender needs are not in the running context's token. */
function missingScopes_() {
  var needed = ['firebase.database', 'firebase.messaging', 'userinfo.email', 'script.external_request'];
  var res = UrlFetchApp.fetch(
    'https://oauth2.googleapis.com/tokeninfo?access_token=' + encodeURIComponent(ScriptApp.getOAuthToken()),
    { muteHttpExceptions: true }
  );
  if (res.getResponseCode() !== 200) return 'unknown';
  var granted = String(JSON.parse(res.getContentText()).scope || '');
  return needed.filter(function (s) { return granted.indexOf(s) < 0; });
}

/** The HTTP status of a shallow read, which is what every push depends on. */
function dbStatus_(url) {
  return UrlFetchApp.fetch(url + '/lists.json?shallow=true', {
    headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() },
    muteHttpExceptions: true,
  }).getResponseCode();
}

/**
 * **Run this from the editor after any change to `appsscript.json`, not `doGet`.**
 *
 * `doGet` only builds a string, so it needs no scope at all and Apps Script never asks for one
 * — which makes a missing `firebase.database` grant look like a working script. This function
 * touches every scope the sender needs (`UrlFetchApp`, and the OAuth token against the
 * database), so the consent screen appears; tick **every** box. It then prints exactly what is
 * wrong, which saves guessing at a `forbidden` later:
 *
 *   Wykonania / Executions → the log shows each property and the real HTTP status of a read.
 *
 * 200 → the sender can read the database. 401/403 → the `firebase.database` scope is not in
 * force. 404 → `FIREBASE_DB_URL` is wrong (a trailing slash or the wrong region).
 */
function selfTest() {
  var props = PropertiesService.getScriptProperties();
  ['FIREBASE_API_KEY', 'FIREBASE_PROJECT_ID', 'FIREBASE_DB_URL'].forEach(function (key) {
    console.log('%s: %s', key, props.getProperty(key) ? 'set' : 'MISSING');
  });

  // What the script's token actually carries, which is the thing worth seeing: a manifest
  // change that was never re-authorised looks exactly like a correct one from in here.
  var token = ScriptApp.getOAuthToken();
  var info = UrlFetchApp.fetch(
    'https://oauth2.googleapis.com/tokeninfo?access_token=' + encodeURIComponent(token),
    { muteHttpExceptions: true }
  );
  if (info.getResponseCode() === 200) {
    var granted = String(JSON.parse(info.getContentText()).scope || '').split(' ');
    console.log('granted scopes:\n  %s', granted.join('\n  '));
    ['firebase.database', 'firebase.messaging', 'userinfo.email', 'script.external_request'].forEach(function (needed) {
      var has = granted.some(function (s) { return s.indexOf(needed) >= 0; });
      if (!has) console.error('MISSING scope: %s', needed);
    });
  }

  var url = props.getProperty('FIREBASE_DB_URL');
  if (!url) return;
  if (url.slice(-1) === '/') console.warn('FIREBASE_DB_URL ends in a slash; remove it');

  // Shallow: keys only, so this downloads nothing however many lists there are.
  var res = UrlFetchApp.fetch(url + '/lists.json?shallow=true', {
    headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() },
    muteHttpExceptions: true,
  });
  var code = res.getResponseCode();
  console.log('database read: HTTP %s', code);
  if (code === 200) {
    console.log('OK — the sender can read the database. Now: Deploy → Manage deployments → New version.');
  } else {
    console.error('%s', res.getContentText().slice(0, 300));
    console.error(
      code === 404
        ? 'FIREBASE_DB_URL looks wrong.'
        : 'A scope is missing above, or was never re-authorised. The database REST API needs BOTH ' +
          'firebase.database AND userinfo.email; with only the first it answers 401.'
    );
  }
}

/** One FCM v1 data message. Returns 'ok', 'gone' (unregistered) or 'failed'. */
function sendTo_(props, token, data) {
  var res = UrlFetchApp.fetch(
    'https://fcm.googleapis.com/v1/projects/' + props.getProperty('FIREBASE_PROJECT_ID') + '/messages:send',
    {
      method: 'post',
      contentType: 'application/json',
      headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() },
      payload: JSON.stringify({
        message: {
          token: token,
          // High priority because a notification follows at once (PLAN.md *Battery policy*).
          android: { priority: 'high' },
          // Data only, every value a string (FCM v1 requirement). No notification block: the
          // app builds its own, so it can stay silent when the list is already on screen.
          data: data,
        },
      }),
      muteHttpExceptions: true,
    }
  );
  var code = res.getResponseCode();
  if (code === 200) return 'ok';
  if (code === 404) return 'gone';
  var text = res.getContentText();
  if (code === 400 && text.indexOf('INVALID_ARGUMENT') >= 0 && text.indexOf('token') >= 0) return 'gone';
  return 'failed';
}

/**
 * Firebase Auth REST accounts:lookup rejects an expired, forged or foreign-project token, so a
 * 200 with a user is the verification. Returns the uid or null.
 */
function verifyIdToken_(idToken, apiKey) {
  if (!idToken || !apiKey) return null;
  var res = UrlFetchApp.fetch(
    'https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=' + encodeURIComponent(apiKey),
    {
      method: 'post',
      contentType: 'application/json',
      payload: JSON.stringify({ idToken: idToken }),
      muteHttpExceptions: true,
    }
  );
  if (res.getResponseCode() !== 200) return null;
  var users = JSON.parse(res.getContentText()).users;
  return users && users.length ? users[0].localId : null;
}

/**
 * Told apart from „the node is empty": a 403 (the firebase.database scope was never granted)
 * or a 404 (FIREBASE_DB_URL is wrong) is a deployment fault, not an answer.
 */
var DB_UNREACHABLE = { unreachable: true };

/** A database read as the project owner: the rules do not apply to this token. */
function dbGet_(props, path, options) {
  var url = props.getProperty('FIREBASE_DB_URL') + path + '.json';
  if (options && options.shallow) url += '?shallow=true';
  var res = UrlFetchApp.fetch(url, {
    headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() },
    muteHttpExceptions: true,
  });
  if (res.getResponseCode() !== 200) {
    console.error('database read failed: %s %s', res.getResponseCode(), res.getContentText().slice(0, 200));
    return DB_UNREACHABLE;
  }
  var text = res.getContentText();
  return text === 'null' || text === '' ? null : JSON.parse(text);
}

function dbDelete_(props, path) {
  UrlFetchApp.fetch(props.getProperty('FIREBASE_DB_URL') + path + '.json', {
    method: 'delete',
    headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() },
    muteHttpExceptions: true,
  });
}

/**
 * At most MAX_PUSHES_PER_MINUTE per list (STATE.md decision 99), so a phone in a loop cannot
 * spend the day's URL fetches. CacheService is per script, shared by every execution.
 */
function allow_(listId) {
  var cache = CacheService.getScriptCache();
  var key = 'rate:' + listId;
  var used = Number(cache.get(key) || 0);
  if (used >= MAX_PUSHES_PER_MINUTE) return false;
  cache.put(key, String(used + 1), 60);
  return true;
}

/** A count as the client sent it: a whole number, never negative, never absurd. */
function count_(value) {
  var n = Math.floor(Number(value));
  if (!isFinite(n) || n < 0) return 0;
  return Math.min(n, MAX_COUNT);
}

function reply_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
