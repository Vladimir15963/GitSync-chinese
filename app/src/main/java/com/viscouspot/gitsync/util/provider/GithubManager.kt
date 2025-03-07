package com.viscouspot.gitsync.util.provider

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.viscouspot.gitsync.Secrets
import com.viscouspot.gitsync.util.Helper
import com.viscouspot.gitsync.util.LogType
import com.viscouspot.gitsync.util.Logger.log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class GithubManager(private val context: Context) : GitProviderManager {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    override val oAuthSupport = true

    companion object {
        private const val DOMAIN = "github.com"
    }

    override fun launchOAuthFlow() {
        val fullAuthUrl = "https://${DOMAIN}/login/oauth/authorize?client_id=${Secrets.GITHUB_CLIENT_ID}&scope=repo%20workflow&state=${UUID.randomUUID()}"

        log(LogType.GithubOAuthFlow, "Launching Flow")
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fullAuthUrl)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    override fun getOAuthCredentials(uri: Uri?, setCallback: (username: String?, accessToken: String?) -> Unit) {
        if (uri == null) return

        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")

        if (code == null || state == null) return

        if (!Helper.isNetworkAvailable(context)) {
            return
        }
        val accessTokenRequest: Request = Request.Builder()
            .url("https://${DOMAIN}/login/oauth/access_token?client_id=${Secrets.GITHUB_CLIENT_ID}&client_secret=${Secrets.GITHUB_CLIENT_SECRET}&code=$code&state=$state")
            .post("".toRequestBody())
            .addHeader("Accept", "application/json")
            .build()

        client.newCall(accessTokenRequest).enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) {
                log(context, LogType.GithubAuthCredentials, e)

                setCallback.invoke(null, null)
            }

            override fun onResponse(call: Call, response: Response) {
                log(LogType.GithubAuthCredentials, "Auth Token Obtained")
                val accessToken = JSONObject(response.body?.string() ?: "").getString("access_token")

                getUsername(accessToken) {
                    setCallback.invoke(it, accessToken)
                }
            }
        })
    }

    private fun getUsername(accessToken: String, successCallback: (username: String) -> Unit) {
        if (!Helper.isNetworkAvailable(context)) {
            return
        }
        val profileRequest: Request = Request.Builder()
            .url("https://api.${DOMAIN}/user")
            .addHeader("Accept", "application/json")
            .addHeader("Authorization", "token $accessToken")
            .build()

        log(LogType.GithubAuthCredentials, "Getting User Profile")
        client.newCall(profileRequest).enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) {
                log(context, LogType.GithubAuthCredentials, e)
            }

            override fun onResponse(call: Call, response: Response) {
                val json = JSONObject(response.body?.string().toString())
                val username = json.getString("login")

                log(LogType.GithubAuthCredentials, "Username Retrieved")
                successCallback.invoke(username)
            }

        })
    }

    override fun getRepos(accessToken: String, updateCallback: (repos: List<Pair<String, String>>) -> Unit, nextPageCallback: (nextPage: (() -> Unit)?) -> Unit): Boolean {
        log(LogType.GetRepos, "Getting User Repos")
        getReposRequest(accessToken, "https://api.${DOMAIN}/user/repos", updateCallback, nextPageCallback)

        return true
    }

    private fun getReposRequest(accessToken: String, url: String, updateCallback: (repos: List<Pair<String, String>>) -> Unit, nextPageCallback: (nextPage: (() -> Unit)?) -> Unit) {
        if (!Helper.isNetworkAvailable(context)) {
            return
        }
        try {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .addHeader("Accept", "application/json")
                    .addHeader("Authorization", "token $accessToken")
                    .build()
            ).enqueue(object: Callback {
                override fun onFailure(call: Call, e: IOException) {
                    log(context, LogType.GetRepos, e)
                }

                override fun onResponse(call: Call, response: Response) {
                    log(LogType.GetRepos, "Repos Received")

                    val jsonArray = JSONArray(response.body?.string())
                    val repoMap: MutableList<Pair<String, String>> = mutableListOf()

                    val link = response.headers["link"] ?: ""

                    if (link != "") {
                        val regex = "<([^>]+)>; rel=\"next\"".toRegex()

                        val match = regex.find(link)
                        val result = match?.groups?.get(1)?.value

                        val nextLink = result ?: ""
                        if (nextLink != "") {
                            nextPageCallback {
                                getReposRequest(accessToken, nextLink, updateCallback, nextPageCallback)
                            }
                        } else {
                            nextPageCallback(null)
                        }
                    }

                    for (i in 0..<jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val name = obj.getString("name")
                        val cloneUrl = obj.getString("clone_url")
                        repoMap.add(Pair(name, cloneUrl))
                    }

                    updateCallback.invoke(repoMap)
                }
            })
        } catch (e: Throwable) {
            nextPageCallback(null)
            updateCallback.invoke(listOf())
            log(context, LogType.GetRepos, e)
        }
    }
}