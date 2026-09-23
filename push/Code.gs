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
  var started = Date.now();
  var props = PropertiesService.getScriptProperties();
  var body;
  try {
    body = JSON.parse(e.postData.contents);
  } catch (err) {
    return reply_({ ok: false, error: 'bad request' });
  }

  var uid = verifyIdToken_(body.idToken, props.getProperty('FIREBASE_API_KEY'));
  if (!uid) return reply_({ ok: false, error: 'unauthenticated' });

  var listId = String(body.listId || '');
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(listId)) return reply_({ ok: false, error: 'bad request' });
  var kind = body.kind === 'shared' ? 'shared' : 'changes';

  // The caller must be on the list. The owner counts as a member: the app writes an owner
  // entry the moment a list is shared (RemoteWrites.share).
  var members = dbGet_(props, '/lists/' + listId + '/members') || {};
  if (!members[uid]) return reply_({ ok: false, error: 'forbidden' });

  if (!allow_(listId)) return reply_({ ok: true, skipped: 'rate' });

  var counts = {
    added: count_(body.added),
    checked: count_(body.checked),
    changed: count_(body.changed),
  };
  var total = counts.added + counts.checked + counts.changed;
  if (kind === 'changes' && total === 0) return reply_({ ok: true, skipped: 'nothing' });

  var actor = dbGet_(props, '/users/' + uid + '/name') || '';
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
    var tokens = dbGet_(props, '/fcmTokens/' + memberUid, { shallow: true }) || {};
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

/** Health check: GET the deployment URL. */
function doGet() {
  return reply_({ ok: true });
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

/** A database read as the project owner: the rules do not apply to this token. */
function dbGet_(props, path, options) {
  var url = props.getProperty('FIREBASE_DB_URL') + path + '.json';
  if (options && options.shallow) url += '?shallow=true';
  var res = UrlFetchApp.fetch(url, {
    headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() },
    muteHttpExceptions: true,
  });
  if (res.getResponseCode() !== 200) return null;
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
