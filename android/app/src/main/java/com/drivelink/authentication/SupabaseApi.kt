package com.drivelink.authentication

import android.content.Context
import com.drivelink.config.AppConfig
import com.drivelink.vehicles.Vehicle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class ApiException(message: String) : Exception(message)

data class Session(val accessToken: String, val refreshToken: String, val expiresAtSec: Long, val userId: String, val email: String)

/** Stores the Supabase session in private app storage. */
class SessionStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("drivelink_session", Context.MODE_PRIVATE)
    fun load(): Session? {
        val a = p.getString("a", null) ?: return null
        return Session(a, p.getString("r", "") ?: "", p.getLong("e", 0), p.getString("u", "") ?: "", p.getString("m", "") ?: "")
    }
    fun save(s: Session) = p.edit().putString("a", s.accessToken).putString("r", s.refreshToken)
        .putLong("e", s.expiresAtSec).putString("u", s.userId).putString("m", s.email).apply()
    fun clear() = p.edit().clear().apply()
}

/** Minimal Supabase client (GoTrue + PostgREST + Edge Functions) over HTTPS. Uses the public anon key only. */
object SupabaseApi {
    private val http = OkHttpClient()
    private val JSON = "application/json".toMediaType()

    private suspend fun call(method: String, path: String, body: String? = null, token: String? = null, prefer: String? = null): String =
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(AppConfig.supabaseUrl + path)
                .header("apikey", AppConfig.supabaseAnonKey)
                .header("Authorization", "Bearer ${token ?: AppConfig.supabaseAnonKey}")
            if (prefer != null) b.header("Prefer", prefer)
            val needsBody = method == "POST" || method == "PUT" || method == "PATCH"
            b.method(method, if (body != null) body.toRequestBody(JSON) else if (needsBody) "".toRequestBody(JSON) else null)
            try {
                http.newCall(b.build()).execute().use { r ->
                    val txt = r.body?.string().orEmpty()
                    if (!r.isSuccessful) throw ApiException(errorMessage(txt, r.code))
                    txt
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                throw ApiException(e.message ?: "Network error")
            }
        }

    private fun errorMessage(txt: String, code: Int): String = try {
        val o = JSONObject(txt)
        listOf("msg", "message", "error_description", "error").firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() } } ?: "Request failed ($code)"
    } catch (_: Exception) { "Request failed ($code)" }

    private fun sessionFrom(o: JSONObject): Session {
        val user = o.getJSONObject("user")
        return Session(o.getString("access_token"), o.optString("refresh_token"),
            System.currentTimeMillis() / 1000 + o.optLong("expires_in", 3600), user.getString("id"), user.optString("email"))
    }

    // ---- auth ----
    suspend fun signIn(email: String, password: String): Session =
        sessionFrom(JSONObject(call("POST", "/auth/v1/token?grant_type=password",
            JSONObject().put("email", email).put("password", password).toString())))

    /** Returns true when a session was created immediately, false when email verification is required. */
    suspend fun signUp(email: String, password: String, fullName: String): Boolean {
        val r = JSONObject(call("POST", "/auth/v1/signup", JSONObject().put("email", email).put("password", password)
            .put("data", JSONObject().put("full_name", fullName)).toString()))
        return r.has("access_token")
    }

    suspend fun refresh(s: Session): Session =
        sessionFrom(JSONObject(call("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", s.refreshToken).toString())))

    suspend fun recover(email: String) { call("POST", "/auth/v1/recover", JSONObject().put("email", email).toString()) }
    suspend fun changePassword(token: String, pw: String) { call("PUT", "/auth/v1/user", JSONObject().put("password", pw).toString(), token) }

    // ---- profile & settings ----
    suspend fun getProfile(token: String, uid: String): JSONObject =
        JSONArray(call("GET", "/rest/v1/profiles?select=full_name,phone_number&id=eq.$uid", token = token)).optJSONObject(0) ?: JSONObject()

    suspend fun updateProfile(token: String, uid: String, name: String, phone: String) {
        val b = JSONObject().put("full_name", name).put("phone_number", if (phone.isBlank()) JSONObject.NULL else phone)
        call("PATCH", "/rest/v1/profiles?id=eq.$uid", b.toString(), token)
    }

    suspend fun getSettings(token: String, uid: String): JSONObject =
        JSONArray(call("GET", "/rest/v1/user_settings?select=*&user_id=eq.$uid", token = token)).optJSONObject(0) ?: JSONObject()

    suspend fun updateSettings(token: String, uid: String, fields: JSONObject) {
        call("PATCH", "/rest/v1/user_settings?user_id=eq.$uid", fields.toString(), token)
    }

    // ---- vehicles ----
    suspend fun listVehicles(token: String): List<Vehicle> {
        val arr = JSONArray(call("GET", "/rest/v1/vehicles?select=*&order=created_at", token = token))
        return (0 until arr.length()).map { Vehicle.from(arr.getJSONObject(it)) }
    }

    suspend fun addVehicle(token: String, uid: String, f: Map<String, String>): Vehicle {
        val o = JSONObject()
        f.forEach { (k, v) -> if (v.isNotBlank()) o.put(k, if (k == "manufacturing_year") v.toInt() else v) }
        o.put("drive_link_vehicle_id", "PENDING") // replaced by a database trigger; the client never chooses it
        o.put("owner_id", uid)                    // replaced by the trigger with auth.uid()
        val r = JSONArray(call("POST", "/rest/v1/vehicles", o.toString(), token, "return=representation"))
        return Vehicle.from(r.getJSONObject(0))
    }

    suspend fun deleteVehicle(token: String, id: String) { call("DELETE", "/rest/v1/vehicles?id=eq.$id", token = token) }

    // ---- edge functions ----
    /** Returns (payloadB64, sigB64) for a verified emergency vehicle, or throws. */
    suspend fun issueCredential(token: String, vehicleId: String): Pair<String, String> {
        val o = JSONObject(call("POST", "/functions/v1/issue-credential", JSONObject().put("vehicle_id", vehicleId).toString(), token))
        return o.getString("payload") to o.getString("sig")
    }

    suspend fun deleteAccount(token: String) { call("POST", "/functions/v1/delete-account", "{}", token) }
}
