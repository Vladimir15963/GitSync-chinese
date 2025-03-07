package com.viscouspot.gitsync.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.Html
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.viscouspot.gitsync.MainActivity
import com.viscouspot.gitsync.R
import com.viscouspot.gitsync.ui.dialog.BaseDialog
import com.viscouspot.gitsync.util.Logger.log
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.random.Random

object Helper {
    const val CONFLICT_NOTIFICATION_ID = 1756

    fun makeToast(context: Context, message: String, length: Int = Toast.LENGTH_SHORT) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post { Toast.makeText(context, message, length).show() }
    }

    fun networkRequired(context: Context) {
        log(LogType.Sync, "Network Connection Required!")
        makeToast(
            context,
            context.getString(R.string.network_unavailable),
            Toast.LENGTH_LONG
        )
    }

    fun extractConflictSections(context: Context, file: File, add: (text: String) -> Unit) {
        val conflictBuilder = StringBuilder()
        var inConflict = false

        BufferedReader(InputStreamReader(FileInputStream(file))).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                when {
                    line!!.trim().startsWith(context.getString(R.string.conflict_end)) -> {
                        conflictBuilder.append(line)
                        add(conflictBuilder.toString())
                        conflictBuilder.clear()
                        inConflict = false
                    }
                    inConflict -> {
                        conflictBuilder.append(line).append("\n")
                    }
                    line!!.trim().startsWith(context.getString(R.string.conflict_start)) -> {
                        inConflict = true
                        conflictBuilder.append(line).append("\n")
                    }
                    else -> {
                        add(line.toString())
                    }
                }
            }

            if (conflictBuilder.isNotEmpty()) {
                add(conflictBuilder.toString())
            }
        }
    }

    @Suppress("DEPRECATION")
    fun isNetworkAvailable(context: Context, toastMessage: String = "Network unavailable!\nRetry when reconnected"): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nw = connectivityManager.activeNetwork ?: return false
            val actNw = connectivityManager.getNetworkCapabilities(nw) ?: return false
            when {
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> true
                else -> {
                    makeToast(context, toastMessage)
                    false
                }
            }
        } else {
            val networkInfo = connectivityManager.activeNetworkInfo
            if (networkInfo != null && networkInfo.isConnected) {
                true
            } else {
                makeToast(context, toastMessage)
                false
            }
        }
    }

    fun sendCheckoutConflictNotification(context: Context) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val channelId = "git_sync_bug_channel"
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                channelId,
                "Git Sync Bug",
                NotificationManager.IMPORTANCE_HIGH
            )
        } else {
            TODO("VERSION.SDK_INT < O")
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)

        val intent = Intent(context, MainActivity::class.java)
        val buttonPendingIntent = PendingIntent.getActivity(context, Random.nextInt(0, 100), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.merge_conflict)
            .setContentTitle("<Merge Conflict> Tap to fix")
            .setContentText("There is an irreconcilable difference between the local and remote changes")
            .setContentIntent(buttonPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        with(NotificationManagerCompat.from(context)) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                makeToast(context, "${context.getString(R.string.report_bug)} ${context.getString(
                    R.string.enable_notifications)}")
                return
            }

            notify(CONFLICT_NOTIFICATION_ID, builder.build())
        }
    }

    fun getDirSelectionLauncher(activityResultLauncher: ActivityResultCaller, context: Context, callback: ((dirUri: Uri?) -> Unit)): ActivityResultLauncher<Uri?> {
        return activityResultLauncher.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                log(LogType.SelectDirectory, "Uri received $uri")
                val uriPath = getPathFromUri(context, it)
                val directory = File(uriPath)
                log(LogType.SelectDirectory, "Path selected: $uriPath")

                if (!directory.exists() || !directory.isDirectory) {
                    log(LogType.SelectDirectory, "Directory does not exist!")
                    callback.invoke(null)
                    return@let
                }

                try {
                    val testFile = File(directory, "test${System.currentTimeMillis()}.txt")
                    testFile.createNewFile()
                    log(LogType.SelectDirectory, "Test file created: ${testFile.absolutePath}")
                    testFile.delete()
                    log(LogType.SelectDirectory, "Test file deleted: ${testFile.absolutePath}")
                } catch (e: IOException) {
                    log(context, LogType.SelectDirectory, e)
                    e.printStackTrace()
                    callback.invoke(null)
                    return@let
                }

                try {
                    val configFile = File(directory, context.getString(R.string.git_config_path))
                    if (configFile.exists()) {
                        log(LogType.SelectDirectory, "Config file exists: ${configFile.absolutePath}")
                        configFile.readText()
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                    log(context, LogType.SelectDirectory, e)
                    callback.invoke(null)
                    return@let
                }

                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)

                log(LogType.SelectDirectory, "Directory selected successfully")
                callback.invoke(uri)
            }
        }
    }
    @SuppressLint("ObsoleteSdkInt")
    fun getPathFromUri(context: Context, uri: Uri, failureCallback: (() -> Unit)? = null): String {
        try {
            val docUriTree = DocumentsContract.buildDocumentUriUsingTree(
                uri,
                DocumentsContract.getTreeDocumentId(uri)
            )

            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && DocumentsContract.isDocumentUri(
                    context,
                    docUriTree
                ) -> {
                    when {
                        isExternalStorageDocument(docUriTree) -> {
                            val docId = DocumentsContract.getDocumentId(docUriTree)
                            val split = docId.split(":")
                            val type = split[0]

                            if ("primary".equals(type, ignoreCase = true)) {
                                return "${Environment.getExternalStorageDirectory()}/${split[1]}"
                            } else {
                                val externalStorageVolumes = context.getExternalFilesDirs(null)
                                for (externalFile in externalStorageVolumes) {
                                    val path = externalFile.absolutePath
                                    if (path.contains(type)) {
                                        val subPath = path.substringBefore("/Android")
                                        return "$subPath/${split[1]}"
                                    }
                                }
                            }
                        }

                        isDownloadsDocument(docUriTree) -> {
                            val id = DocumentsContract.getDocumentId(docUriTree)
                            val contentUri = ContentUris.withAppendedId(
                                Uri.parse("content://downloads/public_downloads"), id.toLong()
                            )

                            return getDataColumn(context, contentUri, null, null)
                        }

                        isMediaDocument(docUriTree) -> {
                            val docId = DocumentsContract.getDocumentId(docUriTree)
                            val split = docId.split(":")
                            val type = split[0]

                            var contentUri: Uri? = null
                            when (type) {
                                "image" -> contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                                "video" -> contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                "audio" -> contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                            }

                            val selection = "_id=?"
                            val selectionArgs = arrayOf(split[1])

                            return getDataColumn(context, contentUri, selection, selectionArgs)
                        }
                    }
                }

                "content".equals(docUriTree.scheme, ignoreCase = true) -> {
                    return when {
                        isGooglePhotosUri(docUriTree) -> uri.lastPathSegment ?: ""
                        else -> getDataColumn(context, docUriTree, null, null)
                    }
                }

                "file".equals(docUriTree.scheme, ignoreCase = true) -> {
                    return docUriTree.path ?: ""
                }
            }
        } catch (e: Throwable) {
            failureCallback?.invoke()
            return ""
        }

        failureCallback?.invoke()
        return ""
    }

    private fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    private fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    private fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }

    private fun isGooglePhotosUri(uri: Uri): Boolean {
        return "com.google.android.apps.photos.content" == uri.authority
    }

    private fun getDataColumn(
        context: Context,
        uri: Uri?,
        selection: String?,
        selectionArgs: Array<String>?
    ): String {
        var cursor: Cursor? = null
        val column = "_data"
        val projection = arrayOf(column)

        try {
            cursor = context.contentResolver.query(uri!!, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndexOrThrow(column)
                return cursor.getString(index)
            }
        } finally {
            cursor?.close()
        }

        return ""
    }

    fun isValidGitRepo(url: String, ssh: Boolean = false): String? {
        val pattern = "^(${if (ssh) "ssh://[^@]+@|git@" else "https?://"})[a-zA-Z0-9.-]+([:/])(\\S+)/(\\S+)(\\.git)?$"
        val regex = Regex(pattern)

        return when {
            !regex.matches(url) -> "URL must be a valid Git URL (HTTP/S, SSH, or git@)"
            else -> null
        }
    }

    fun generateSSHKeyPair(): Pair<String, String> {
        val jsch = JSch()
        val keyPair = KeyPair.genKeyPair(jsch, KeyPair.RSA, 4096)

        val privateKeyStream = ByteArrayOutputStream()
        val publicKeyStream = ByteArrayOutputStream()

        keyPair.writePrivateKey(privateKeyStream)
        keyPair.writePublicKey(publicKeyStream, "")

        val privateKey = String(privateKeyStream.toByteArray(), StandardCharsets.UTF_8)
        val publicKey = String(publicKeyStream.toByteArray(), StandardCharsets.UTF_8)

        return Pair(privateKey, publicKey)
    }

    fun showContributeDialog(context: Context, repoManager: RepoManager, callback: () -> Unit) {
        if (repoManager.hasContributed()) {
            callback()
            return
        }

        BaseDialog(context).apply {
            setTitle(context.getString(R.string.contribute_title))
            setMessage(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Html.fromHtml(
                        context.getString(R.string.contribute_msg),
                        Html.FROM_HTML_MODE_LEGACY
                    )
                } else {
                    Html.fromHtml(
                        context.getString(R.string.contribute_msg),
                    )
                }
            )
            setCancelable(0)
            setPositiveButton(R.string.support_now) { _, _ ->
                repoManager.setHasContributed()
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.contribute_link)))
                context.startActivity(browserIntent)
            }
            setNegativeButton(R.string.support_promise) { _, _ ->
                repoManager.setHasContributed()
                callback()
            }
        }.show()
    }
}

fun EditText.rightDrawable(@DrawableRes id: Int? = 0) {
    val drawable = if (id !=null) ContextCompat.getDrawable(context, id) else null
    val size = resources.getDimensionPixelSize(R.dimen.text_size_lg)
    drawable?.setBounds(0, 0, size, size)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        this.compoundDrawableTintList
    }
    this.setCompoundDrawables(null, null, drawable, null)
}

