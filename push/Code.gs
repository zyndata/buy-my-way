/**
 * Buy My Way — push sender (Google Apps Script web app).
 *
 * Phase 0 skeleton: POST {idToken, sentAt} → the Firebase ID token is validated, then one FCM
 * v1 data message goes to the single token held in the script property FCM_TOKEN. Phase 9
 * replaces that hard-coded target with the list's members read from the Realtime Database.
 *
 * Script properties (Project settings → Script properties; ids, not secrets):
 *   FIREBASE_API_KEY     the Web API key of the Firebase project
 *   FIREBASE_PROJECT_ID  e.g. buy-my-way
 *   FCM_TOKEN            the one device to push to (Phase 0 only)
 *
 * No service-account key: the script's Cloud project is the Firebase project, so
 * ScriptApp.getOAuthToken() carries the firebase.messaging scope (appsscript.json).
 */

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
  var verifiedMs = Date.now() - started;

  var res = UrlFetchApp.fetch(
    'https://fcm.googleapis.com/v1/projects/' + props.getProperty('FIREBASE_PROJECT_ID') + '/messages:send',
    {
      method: 'post',
      contentType: 'application/json',
      headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() },
      payload: JSON.stringify({
        message: {
          token: props.getProperty('FCM_TOKEN'),
          android: { priority: 'high' },
          // Data only, all values strings (FCM v1 requirement).
          data: { kind: 'spike', sentAt: String(body.sentAt || ''), scriptMs: String(Date.now() - started) },
        },
      }),
      muteHttpExceptions: true,
    }
  );
  var code = res.getResponseCode();
  return reply_({
    ok: code === 200,
    fcm: code,
    fcmError: code === 200 ? undefined : res.getContentText().slice(0, 300),
    verifiedMs: verifiedMs,
    scriptMs: Date.now() - started,
  });
}

/** Health check: GET the deployment URL. */
function doGet() {
  return reply_({ ok: true });
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

function reply_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
