package dev.gorny.buymyway.spike

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class DriveHttpException(val code: Int, body: String) : Exception("HTTP $code: $body")

/** The handful of Drive REST v3 calls the spike needs, over OkHttp — the shape Phase 4 will take. */
class Drive(private val token: () -> String) {
    private val http = OkHttpClient()
    private val json = "application/json; charset=utf-8".toMediaType()
    private val api = "https://www.googleapis.com/drive/v3"
    private val upload = "https://www.googleapis.com/upload/drive/v3"

    private suspend fun call(req: Request.Builder): String = withContext(Dispatchers.IO) {
        http.newCall(req.header("Authorization", "Bearer ${token()}").build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw DriveHttpException(r.code, body)
            body
        }
    }

    suspend fun createFolder(name: String, parent: String?): String {
        val meta = JSONObject()
            .put("name", name)
            .put("mimeType", "application/vnd.google-apps.folder")
        if (parent != null) meta.put("parents", JSONArray().put(parent))
        val r = call(Request.Builder().url("$api/files?fields=id").post(meta.toString().toRequestBody(json)))
        return JSONObject(r).getString("id")
    }

    /** Multipart create; [parent] may be "appDataFolder". Returns the new file's id. */
    suspend fun createFile(name: String, parent: String, content: String, mime: String = "application/json"): String {
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(parent))
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta.toString().toRequestBody(json))
            .addPart(content.toRequestBody(mime.toMediaType()))
            .build()
        val r = call(Request.Builder().url("$upload/files?uploadType=multipart&fields=id").post(body))
        return JSONObject(r).getString("id")
    }

    suspend fun share(fileId: String, email: String, role: String): String = call(
        Request.Builder()
            .url("$api/files/$fileId/permissions?sendNotificationEmail=false&fields=id,role,emailAddress")
            .post(JSONObject().put("type", "user").put("role", role).put("emailAddress", email).toString().toRequestBody(json)),
    )

    suspend fun list(q: String, spaces: String = "drive"): String = call(
        Request.Builder().url(
            "$api/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", q)
                .addQueryParameter("spaces", spaces)
                .addQueryParameter("fields", "files(id,name,mimeType,version,modifiedTime,owners(emailAddress),capabilities(canEdit))")
                .build(),
        ),
    )

    suspend fun metadata(fileId: String): String =
        call(Request.Builder().url("$api/files/$fileId?fields=id,name,version,modifiedTime,capabilities(canEdit,canShare)"))

    suspend fun read(fileId: String): String = call(Request.Builder().url("$api/files/$fileId?alt=media"))

    suspend fun update(fileId: String, content: String): String = call(
        Request.Builder().url("$upload/files/$fileId?uploadType=media&fields=id,version,modifiedTime")
            .patch(content.toRequestBody(json)),
    )

}
