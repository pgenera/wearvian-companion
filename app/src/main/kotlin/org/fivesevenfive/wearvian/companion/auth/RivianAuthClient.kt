package org.fivesevenfive.wearvian.companion.auth

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.fivesevenfive.wearvian.companion.util.logi
import org.fivesevenfive.wearvian.companion.util.logw
import org.json.JSONObject

/**
 * Minimal Rivian authentication client: turns email + password (+ MFA OTP) into
 * the session tokens needed for `getUserInfo` / `EnrollPhone`.
 *
 *   CreateCSRFToken -> Login -> (if MFA) LoginWithOTP
 *
 * This is a Kotlin/OkHttp port of the reference Python broker
 * (`wearvian/auth-server/rivian_auth.py`), itself byte-identical to
 * `bretterer/rivian-python-client` (`src/rivian/rivian.py`). Operation names,
 * query strings and headers are kept identical so behaviour matches the
 * community-tested client. No credentials are persisted by this class.
 */
class RivianAuthClient(
    private val http: OkHttpClient = OkHttpClient(),
) {
    class RivianAuthError(message: String) : Exception(message)

    data class CsrfTokens(val csrfToken: String, val appSessionToken: String)

    /** The tokens needed to call getUserInfo / EnrollPhone. */
    data class SessionTokens(
        val csrfToken: String,
        val appSessionToken: String,
        val userSessionToken: String,
    )

    /** Result of [login]: either complete tokens, or an MFA challenge. */
    sealed interface LoginResult {
        data class Success(val tokens: SessionTokens) : LoginResult
        data class MfaRequired(val otpToken: String) : LoginResult
    }

    private fun post(operation: String, headers: Map<String, String>, payload: String): JSONObject {
        logi("POST $operation -> gateway (headers=${headers.keys})")
        val req = Request.Builder()
            .url(GATEWAY_URL)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(payload.toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            logi("POST $operation <- HTTP ${resp.code}")
            if (resp.code != 200) throw RivianAuthError("HTTP ${resp.code} from Rivian gateway")
            val body = JSONObject(resp.body?.string().orEmpty())
            if (body.has("errors") && !body.isNull("errors")) {
                logw("$operation returned GraphQL errors: ${body.get("errors")}")
                throw RivianAuthError(body.get("errors").toString())
            }
            return body.optJSONObject("data")
                ?: throw RivianAuthError("Missing 'data' in Rivian response")
        }
    }

    fun createCsrfToken(): CsrfTokens {
        val payload = JSONObject()
            .put("operationName", "CreateCSRFToken")
            .put("query", CSRF_QUERY)
            .put("variables", JSONObject.NULL)
            .toString()
        val csrf = post("CreateCSRFToken", BASE_HEADERS, payload).getJSONObject("createCsrfToken")
        logi("createCsrfToken: ok")
        return CsrfTokens(csrf.getString("csrfToken"), csrf.getString("appSessionToken"))
    }

    fun login(csrf: CsrfTokens, email: String, password: String): LoginResult {
        val headers = BASE_HEADERS + mapOf(
            "Csrf-Token" to csrf.csrfToken,
            "A-Sess" to csrf.appSessionToken,
        )
        val payload = JSONObject()
            .put("operationName", "Login")
            .put("query", LOGIN_QUERY)
            .put("variables", JSONObject().put("email", email).put("password", password))
            .toString()
        val login = post("Login", headers, payload).getJSONObject("login")
        val otpToken = login.optString("otpToken", "")
        return if (otpToken.isNotEmpty()) {
            logi("login: MFA required")
            LoginResult.MfaRequired(otpToken)
        } else {
            logi("login: success (no MFA)")
            LoginResult.Success(
                SessionTokens(csrf.csrfToken, csrf.appSessionToken, login.getString("userSessionToken")),
            )
        }
    }

    fun loginWithOtp(
        csrf: CsrfTokens,
        email: String,
        otpCode: String,
        otpToken: String,
    ): SessionTokens {
        val headers = BASE_HEADERS + mapOf(
            "Csrf-Token" to csrf.csrfToken,
            "A-Sess" to csrf.appSessionToken,
        )
        val payload = JSONObject()
            .put("operationName", "LoginWithOTP")
            .put("query", OTP_QUERY)
            .put(
                "variables",
                JSONObject().put("email", email).put("otpCode", otpCode).put("otpToken", otpToken),
            )
            .toString()
        val login = post("LoginWithOTP", headers, payload).getJSONObject("loginWithOTP")
        logi("loginWithOtp: success")
        return SessionTokens(csrf.csrfToken, csrf.appSessionToken, login.getString("userSessionToken"))
    }

    companion object {
        const val GATEWAY_URL = "https://rivian.com/api/gql/gateway/graphql"
        private val JSON = "application/json".toMediaType()
        private const val APOLLO_CLIENT_NAME = "com.rivian.ios.consumer-apollo-ios"

        val BASE_HEADERS = mapOf(
            "User-Agent" to "RivianApp/707 CFNetwork/1237 Darwin/20.4.0",
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "Apollographql-Client-Name" to APOLLO_CLIENT_NAME,
        )

        // GraphQL documents, copied verbatim from rivian-python-client.
        private const val CSRF_QUERY =
            "mutation CreateCSRFToken {\n  createCsrfToken {\n    __typename\n" +
                "    csrfToken\n    appSessionToken\n  }\n}"
        private const val LOGIN_QUERY =
            "mutation Login(\$email: String!, \$password: String!) {\n  login(email: \$email, " +
                "password: \$password) {\n    __typename\n    ... on MobileLoginResponse {\n" +
                "      __typename\n      accessToken\n      refreshToken\n      userSessionToken\n" +
                "    }\n    ... on MobileMFALoginResponse {\n      __typename\n      otpToken\n" +
                "    }\n  }\n}"
        private const val OTP_QUERY =
            "mutation LoginWithOTP(\$email: String!, \$otpCode: String!, \$otpToken: String!) {\n" +
                "  loginWithOTP(email: \$email, otpCode: \$otpCode, otpToken: \$otpToken) {\n" +
                "    __typename\n    ... on MobileLoginResponse {\n      __typename\n" +
                "      accessToken\n      refreshToken\n      userSessionToken\n    }\n  }\n}"
    }
}
