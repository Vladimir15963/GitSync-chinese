package com.viscouspot.gitsync.util

import android.content.Context
import android.net.Uri
import android.os.Looper
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.viscouspot.gitsync.R
import com.viscouspot.gitsync.ui.adapter.Commit
import com.viscouspot.gitsync.util.Helper.makeToast
import com.viscouspot.gitsync.util.Helper.sendCheckoutConflictNotification
import com.viscouspot.gitsync.util.Logger.log
import com.viscouspot.gitsync.util.provider.GitProviderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.InvalidRemoteException
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.api.errors.NoHeadException
import org.eclipse.jgit.api.errors.WrongRepositoryStateException
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.errors.CheckoutConflictException
import org.eclipse.jgit.errors.NotSupportedException
import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.internal.JGitText
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.lib.BatchingProgressMonitor
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.lib.StoredConfig
import org.eclipse.jgit.merge.ResolveMerger
import org.eclipse.jgit.transport.JschConfigSessionFactory
import org.eclipse.jgit.transport.OpenSshConfig
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.eclipse.jgit.api.errors.CheckoutConflictException as ApiCheckoutConflictException
import org.eclipse.jgit.api.errors.TransportException as ApiTransportException

class GitManager(private val context: Context, private val settingsManager: SettingsManager) {
    private fun applyCredentials(command: TransportCommand<*, *>) {
        log(settingsManager.getGitProvider())
        if (settingsManager.getGitProvider() == GitProviderManager.Companion.Provider.SSH) {
            val sshSessionFactory = object : JschConfigSessionFactory() {
                override fun configure(host: OpenSshConfig.Host, session: Session) {
                    session.setConfig("StrictHostKeyChecking", "no")
                    session.setConfig("PreferredAuthentications", "publickey,password")
                    session.setConfig("kex", "diffie-hellman-group-exchange-sha256,ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group14-sha256")
                }

                override fun createDefaultJSch(fs: org.eclipse.jgit.util.FS?): JSch {
                    val jsch = super.createDefaultJSch(fs)
                    jsch.addIdentity("key", settingsManager.getGitSshPrivateKey().toByteArray(), null, null)
                    return jsch
                }
            }

            SshSessionFactory.setInstance(sshSessionFactory)
            command.setTransportConfigCallback { transport ->
                if (transport is SshTransport) {
                    transport.sshSessionFactory = sshSessionFactory
                }
                transport.timeout = 3000
            }
        } else {
            val authCredentials = settingsManager.getGitAuthCredentials()
            command.setCredentialsProvider(UsernamePasswordCredentialsProvider(authCredentials.first, authCredentials.second))
            command.setTransportConfigCallback { transport ->
                transport.timeout = 3000
            }
        }
    }

    fun discardFileChanges(userStorageUri: Uri, filePath: String) {
        try {
            val repo = FileRepository("${Helper.getPathFromUri(context, userStorageUri)}/${context.getString(R.string.git_path)}")
            val git = Git(repo)

            git.checkout().addPath(filePath).call()
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    fun cloneRepository(repoUrl: String, userStorageUri: Uri, taskCallback: (action: String) -> Unit, progressCallback: (progress: Int) -> Unit, failureCallback: (error: String) -> Unit, successCallback: () -> Unit) {
        if (!Helper.isNetworkAvailable(context)) {
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                log(LogType.CloneRepo, "Cloning Repo")

                val monitor = object : BatchingProgressMonitor() {
                    override fun onUpdate(taskName: String?, workCurr: Int) { }

                    override fun onUpdate(
                        taskName: String?,
                        workCurr: Int,
                        workTotal: Int,
                        percentDone: Int,
                    ) {
                        taskCallback(taskName ?: "")
                        progressCallback(percentDone)
                    }

                    override fun onEndTask(taskName: String?, workCurr: Int) { }

                    override fun onEndTask(
                        taskName: String?,
                        workCurr: Int,
                        workTotal: Int,
                        percentDone: Int
                    ) { }
                }

                Git.cloneRepository().apply {
                    setURI(repoUrl)
                    setProgressMonitor(monitor)
                    setDirectory(File(Helper.getPathFromUri(context, userStorageUri)))
                    applyCredentials(this)
                    setRemote(settingsManager.getRemote())
                }.call()

                log(LogType.CloneRepo, "Repository cloned successfully")
                withContext(Dispatchers.Main) {
                    makeToast(context, "Repository cloned successfully")
                }

                successCallback.invoke()
            } catch (e: InvalidRemoteException) {
                failureCallback(context.getString(R.string.invalid_remote))
                return@launch
            } catch (e: TransportException) {
                e.printStackTrace()
                log(e)
                log(e.localizedMessage)
                log(e.cause)
                if (e.stackTraceToString().contains("Cleartext HTTP traffic")) {
                    failureCallback("Git repositories must use SSL to work with this application")
                    return@launch
                }
                failureCallback(e.localizedMessage ?: context.getString(R.string.clone_failed))
                return@launch
            } catch (e: ApiTransportException) {
                e.printStackTrace()
                log(e)
                log(e.localizedMessage)
                log(e.cause)
                if (e.stackTraceToString().contains("Cleartext HTTP traffic")) {
                    failureCallback("Git repositories must use SSL to work with this application")
                    return@launch
                }
                failureCallback(e.localizedMessage ?: context.getString(R.string.clone_failed))
                return@launch
            } catch (e: GitAPIException) {
                e.printStackTrace()
                log(e)
                log(e.localizedMessage)
                log(e.cause)
                failureCallback(context.getString(R.string.clone_failed))
                return@launch
            }
            catch (e: JGitInternalException) {
                if (e.cause is NotSupportedException) {
                    failureCallback(context.getString(R.string.invalid_remote))
                } else {
                    failureCallback(e.localizedMessage ?: context.getString(R.string.clone_failed))
                }
                return@launch
            } catch (e: NullPointerException) {
                if (e.message?.contains("Inflater has been closed") == true) {
                    failureCallback(context.getString(R.string.large_file))
                    return@launch
                }

                log(context, LogType.CloneRepo, e)
            } catch (e: OutOfMemoryError) {
                failureCallback(context.getString(R.string.out_of_memory))
                return@launch
            } catch (e: Throwable) {
                failureCallback(context.getString(R.string.clone_failed))

                log(context, LogType.CloneRepo, e)
            }
        }
    }

    fun forcePull(userStorageUri: Uri): Boolean {
        if (!Helper.isNetworkAvailable(context)) {
            return false
        }
        try {
            log(LogType.ForcePull, "Getting local directory")
            val repo = FileRepository("${Helper.getPathFromUri(context, userStorageUri)}/${context.getString(R.string.git_path)}")
            val git = Git(repo)

            log(LogType.ForcePull, "Fetching changes")
            git.fetch().apply {
                applyCredentials(this)
                setRemote(settingsManager.getRemote())
                setRefSpecs(RefSpec("+refs/heads/*:refs/remotes/origin/*"))
            }.call()

            log(LogType.ForcePull, "Resetting to refs/remotes/origin/${git.repository.branch}")
            git.reset().apply {
                setMode(ResetCommand.ResetType.HARD)
                setRef("refs/remotes/origin/${git.repository.branch}")
            }.call()

            log(LogType.ForcePull, "Cleaning up")
            git.clean().apply {
                setCleanDirectories(true)
            }.call()

            log(LogType.ForcePull, "Closing repository")
            closeRepo(repo)

            return true
        }  catch (e: InvalidRemoteException) {
            handleInvalidRemoteException(e)
        } catch (e: TransportException) {
            handleTransportException(e)
        } catch (e: ApiTransportException) {
            handleTransportException(e)
        } catch (e: Throwable) {
            log(context, LogType.ForcePull, e)
        }
        return false
    }

    fun downloadChanges(userStorageUri: Uri, scheduleNetworkSync: () -> Unit, onSync: (() -> Unit)?): Boolean? {
        if (conditionallyScheduleNetworkSync(scheduleNetworkSync)) {
            return null
        }
        try {
            var returnResult: Boolean? = onSync == null
            log(LogType.PullFromRepo, "Getting local directory")
            val repo = FileRepository("${Helper.getPathFromUri(context, userStorageUri)}/${context.getString(R.string.git_path)}")
            val git = Git(repo)

            log(LogType.PullFromRepo, "Fetching changes")
            val fetchResult = git.fetch().apply {
                applyCredentials(this)
                setRemote(settingsManager.getRemote())
            }.call()

            if (conditionallyScheduleNetworkSync(scheduleNetworkSync)) {
                return null
            }

            if (repo.resolve(Constants.HEAD) == null || repo.resolve(Constants.FETCH_HEAD) == null) return false

            val localHead: ObjectId = repo.resolve(Constants.HEAD)
            val remoteHead: ObjectId = repo.resolve(Constants.FETCH_HEAD)

            if (!fetchResult.trackingRefUpdates.isEmpty() || !localHead.equals(remoteHead)) {
                log(LogType.PullFromRepo, "Pulling changes")
                onSync?.invoke()
                val result = git.pull().apply {
                    applyCredentials(this)
                    setRemote(settingsManager.getRemote())
                }.call()

                if (result.mergeResult.failingPaths != null && result.mergeResult.failingPaths.containsValue(
                        ResolveMerger.MergeFailureReason.DIRTY_WORKTREE)) {
                    log(LogType.PullFromRepo, "Merge conflict")
                    return false
                }

                if (!result.mergeResult.mergeStatus.isSuccessful) {
                    log(LogType.PullFromRepo, "Checkout conflict")
                    sendCheckoutConflictNotification(context)
                    return null
                }

                returnResult = if (result.isSuccessful()) {
                    true
                } else {
                    null
                }
            }

            log(LogType.PullFromRepo, "Closing repository")
            closeRepo(repo)

            return returnResult
        } catch (e: CheckoutConflictException) {
            log(LogType.PullFromRepo, e.stackTraceToString())
            return false
        } catch (e: ApiCheckoutConflictException) {
            log(LogType.PullFromRepo, e.stackTraceToString())
            return false
        }  catch (e: WrongRepositoryStateException) {
            if (e.message?.contains(context.getString(R.string.merging_exception_message)) == true) {
                log(LogType.PullFromRepo, "Merge conflict")
                return false
            }
            log(context, LogType.PullFromRepo, e)
            return null
        } catch (e: InvalidRemoteException) {
            handleInvalidRemoteException(e)
        } catch (e: TransportException) {
            handleTransportException(e)
        } catch (e: ApiTransportException) {
            handleTransportException(e)
        } catch (e: Throwable) {
            log(context, LogType.PullFromRepo, e)
        }
        conditionallyScheduleNetworkSync(scheduleNetworkSync)
        return null
    }

    fun getUncommittedFilePaths(userStorageUri: Uri, gitInstance: Git? = null): List<String> {
        var git = gitInstance
        if (git == null) {
            val repo = FileRepository("${Helper.getPathFromUri(context, userStorageUri)}/${context.getString(R.string.git_path)}")
            git = Git(repo)
        }
        val status = git.status().call()

        return (status.uncommittedChanges.plus(status.untracked)).toList()
    }

    fun forcePush(userStorageUri: Uri): Boolean {
        if (!Helper.isNetworkAvailable(context)) {
            return false
        }
        try {
            var returnResult = false
            log(LogType.ForcePush, "Getting local directory")

            val repo = FileRepository("${Helper.getPathFromUri(context, userStorageUri)}/${context.getString(R.string.git_path)}")
            val git = Git(repo)

            logStatus(git)
            val uncommitted = getUncommittedFilePaths(userStorageUri, git)

            if (uncommitted.isNotEmpty()) {
                log(LogType.ForcePush, "Adding Files to Stage")

                // Adds all uncommitted and untracked files to the index for staging.
                git.add().apply {
                    uncommitted.forEach { addFilepattern(it) }
                }.call()

                // Updates the index to reflect changes in already tracked files, removing deleted files without adding untracked files.
                git.add().apply {
                    uncommitted.forEach { addFilepattern(it) }
                    isUpdate = true
                }.call()

                log(LogType.ForcePush, "Getting current time")

                val formattedDate: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())

                log(LogType.ForcePush, "Committing changes")
                val config: StoredConfig = git.repository.config

                var committerEmail: String? = settingsManager.getAuthorEmail()
                if (committerEmail == null || committerEmail == "") {
                    committerEmail = config.getString("user", null, "email")
                }

                var committerName: String? = settingsManager.getAuthorName()
                if (committerName == null || committerName == "") {
                    committerName = config.getString("user", null, "name")
                }
                if (committerName == null || committerName == "") {
                    committerName = settingsManager.getGitAuthCredentials().first
                }

                git.commit().apply {
                    setCommitter(committerName, committerEmail ?: "")
                    message = settingsManager.getSyncMessage().format(formattedDate)
                }.call()

                returnResult = true
            }

            log(LogType.ForcePush, "Force pushing changes")
            git.push().apply {
                applyCredentials(this)
                setForce(true)
                setRemote(settingsManager.getRemote())
            }.call()

            logStatus(git)

            log(LogType.PushToRepo, "Closing repository")
            closeRepo(repo)

            return returnResult
        } catch (e: InvalidRemoteException) {
            handleInvalidRemoteException(e)
        } catch (e: Throwable) {
            log(context, LogType.PushToRepo, e)
        }
        return false
    }

    fun uploadChanges(userStorageUri: Uri, scheduleNetworkSync: () -> Unit, onSync: () -> Unit, filePaths: List<String>? = null, syncMessage: String? = null): Boolean? {
        if (conditionallyScheduleNetworkSync(scheduleNetworkSync)) {
            return null
        }
        try {
            var returnResult = false
            log(LogType.PushToRepo, "Getting local directory")

            val repo = FileRepository("${Helper.getPathFromUri(context, userStorageUri)}/${context.getString(R.string.git_path)}")
            val git = Git(repo)

            logStatus(git)
            val uncommitted = filePaths ?: getUncommittedFilePaths(userStorageUri, git)

            if (uncommitted.isEmpty()) {
                log(LogType.PushToRepo, "Closing repository")
                closeRepo(repo)
                return false
            }

            onSync.invoke()

            log(LogType.PushToRepo, "Adding Files to Stage")

            // Adds all uncommitted and untracked files to the index for staging.
            git.add().apply {
                uncommitted.forEach { addFilepattern(it) }
            }.call()

            // Updates the index to reflect changes in already tracked files, removing deleted files without adding untracked files.
            git.add().apply {
                uncommitted.forEach { addFilepattern(it) }
                isUpdate = true
            }.call()

            log(LogType.PushToRepo, "Getting current time")

            val formattedDate: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())

            log(LogType.PushToRepo, "Committing changes")
            val config: StoredConfig = git.repository.config

            var committerEmail: String? = settingsManager.getAuthorEmail()
            if (committerEmail == null || committerEmail == "") {
                committerEmail = config.getString("user", null, "email")
            }

            var committerName: String? = settingsManager.getAuthorName()
            if (committerName == null || committerName == "") {
                committerName = config.getString("user", null, "name")
            }
            if (committerName == null || committerName == "") {
                committerName = settingsManager.getGitAuthCredentials().first
            }

            git.commit().apply {
                setCommitter(committerName, committerEmail ?: "")
                message = if (!syncMessage.isNullOrEmpty()) syncMessage else settingsManager.getSyncMessage().format(formattedDate)
            }.call()

            returnResult = true

            if (conditionallyScheduleNetworkSync(scheduleNetworkSync)) {
                return null
            }

            log(LogType.PushToRepo, "Pushing changes")
            val pushResults = git.push().apply {
                applyCredentials(this)
                setRemote(settingsManager.getRemote())
            }.call()
            for (pushResult in pushResults) {
                for (remoteUpdate in pushResult.remoteUpdates) {
                    when (remoteUpdate.status) {
                        RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD -> {
                            log(LogType.PushToRepo, "Attempting rebase on REJECTED_NONFASTFORWARD")
                            logStatus(git)
                            val trackingStatus = BranchTrackingStatus.of(git.repository, git.repository.branch)
                                ?: throw Exception(context.getString(R.string.auto_rebase_failed_exception))

                            if (git.repository.repositoryState == RepositoryState.MERGING || git.repository.repositoryState == RepositoryState.MERGING_RESOLVED) {
                                log(LogType.PushToRepo, "Aborting previous merge to ensure clean state for rebase")
                                git.rebase().apply  {
                                    setOperation(RebaseCommand.Operation.ABORT)
                                }.call()
                            }

                            val rebaseResult = git.rebase().apply  {
                                setUpstream(trackingStatus.remoteTrackingBranch)
                            }.call()

                            logStatus(git)

                            if (!rebaseResult.status.isSuccessful) {
                                git.rebase().apply  {
                                    setOperation(RebaseCommand.Operation.ABORT)
                                }.call()

                                downloadChanges(userStorageUri, scheduleNetworkSync, onSync)
                                return false
                            }
                            break
                        }
                        RemoteRefUpdate.Status.NON_EXISTING -> {
                            throw Exception(context.getString(R.string.non_existing_exception))
                        }
                        RemoteRefUpdate.Status.REJECTED_NODELETE -> {
                            throw Exception(context.getString(R.string.rejected_nodelete_exception))
                        }
                        RemoteRefUpdate.Status.REJECTED_OTHER_REASON -> {
                            val reason = remoteUpdate.message
                            throw Exception(if (reason == null || reason == "") context.getString(R.string.rejected_exception) else context.getString(
                                R.string.rejection_with_reason_exception
                            ).format(reason))
                        }
                        RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED -> {
                            throw Exception(context.getString(R.string.remote_changed_exception))
                        }
                        else -> {}
                    }
                }
            }

            if (Looper.myLooper() == null) {
                Looper.prepare()
            }

            logStatus(git)

            log(LogType.PushToRepo, "Closing repository")
            closeRepo(repo)

            return returnResult
        } catch (e: InvalidRemoteException) {
            handleInvalidRemoteException(e)
        } catch (e: TransportException) {
            handleTransportException(e)
        } catch (e: ApiTransportException) {
            handleTransportException(e)
        } catch (e: Throwable) {
            log(context, LogType.PushToRepo, e)
        }
        conditionallyScheduleNetworkSync(scheduleNetworkSync)
        return null
    }

    private fun conditionallyScheduleNetworkSync(scheduleNetworkSync: () -> Unit): Boolean {
        if (!Helper.isNetworkAvailable(context)) {
            scheduleNetworkSync()
            return true
        }
        return false
    }

    private fun handleInvalidRemoteException(e: Exception) {
        makeToast(context, context.getString(R.string.invalid_remote))
        log(LogType.SyncException, e.stackTraceToString())
    }

    private fun handleTransportException(e: Exception) {
        if (listOf(
            JGitText.get().connectionFailed,
            JGitText.get().connectionTimeOut,
            JGitText.get().transactionAborted,
            JGitText.get().cannotOpenService
        ).any{ e.message.toString().contains(it) } ) {
            return
        }

        if (e.message.toString() == JGitText.get().notFound) {
            handleInvalidRemoteException(e)
            return
        }

        var message = e.message.toString()
        if (listOf(
            JGitText.get().authenticationNotSupported,
            JGitText.get().notAuthorized,
        ).any {
            message = it
            e.message.toString().contains(it)
        }) {
            log(context, LogType.SyncException, Throwable(message))
            return
        }

        log(context, LogType.SyncException, e)
    }

    private fun logStatus(git: Git) {
        val status = git.status().call()
        log(LogType.GitStatus, """
            HasUncommittedChanges: ${status.hasUncommittedChanges()}
            Missing: ${status.missing}
            Modified: ${status.modified}
            Removed: ${status.removed}
            IgnoredNotInIndex: ${status.ignoredNotInIndex}
            Changed: ${status.changed}
            Untracked: ${status.untracked}
            Added: ${status.added}
            Conflicting: ${status.conflicting}
            UncommittedChanges: ${status.uncommittedChanges}
        """.trimIndent())
    }

    fun getRecentCommits(gitDirPath: String?): List<Commit> {
        try {
            if (gitDirPath == null || !File("$gitDirPath/${context.getString(R.string.git_path)}").exists()) return listOf()

            log(LogType.RecentCommits, ".git folder found")
            val commits = mutableListOf<Commit>()

            val repo = FileRepository("$gitDirPath/${context.getString(R.string.git_path)}")
            if (repo.isBare) return listOf()

            Git(repo).let { git ->
                val logCommits = git.log().call()

                for (commit in logCommits.take(10)) {
                    val diffFormatter = DiffFormatter(DisabledOutputStream.INSTANCE)
                    diffFormatter.setRepository(repo)
                    val parent = if (commit.parentCount > 0) commit.getParent(0) else null
                    val diffs = if (parent != null) diffFormatter.scan(parent.tree, commit.tree) else listOf()

                    var additions = 0
                    var deletions = 0
                    for (diff in diffs) {
                        try {
                            val editList = diffFormatter.toFileHeader(diff).toEditList()
                            for (edit in editList) {
                                additions += edit.endB - edit.beginB
                                deletions += edit.endA - edit.beginA
                            }
                        } catch (e: NullPointerException) { log(e.message) }
                    }

                    commits.add(
                        Commit(
                            commit.fullMessage,
                            commit.authorIdent.name,
                            commit.authorIdent.getWhen().time,
                            commit.name,
                            additions,
                            deletions
                        )
                    )
                }
            }

            log(LogType.RecentCommits, "Recent commits retrieved")
            closeRepo(repo)

            return commits
        } catch (e: NoHeadException) {
            log(LogType.RecentCommits, e.message.toString())
        } catch (e: Throwable) {
            log(context, LogType.RecentCommits, e)
        }
        return listOf()
    }

    fun getConflicting(gitDirUri: Uri?): MutableList<String> {
        if (gitDirUri == null) return mutableListOf()

        try {
            val repo = FileRepository(
                "${
                    Helper.getPathFromUri(
                        context,
                        gitDirUri
                    )
                }/${context.getString(R.string.git_path)}"
            )
            val git = Git(repo)
            val status = git.status().call()
            return status.conflicting.toMutableList()
        } catch (e: Throwable) {
            log(context, LogType.RecentCommits, e)
        }
        return mutableListOf()
    }

    fun abortMerge(gitDirUri: Uri?) {
        if (gitDirUri == null) return
        val gitDirPath = Helper.getPathFromUri(context, gitDirUri)

        try {
            val repo = FileRepository("$gitDirPath/${context.getString(R.string.git_path)}")
            val git = Git(repo)

            val mergeHeadFile = File("$gitDirPath/${context.getString(R.string.git_merge_head_path)}")
            if (mergeHeadFile.exists()) {
                git.reset().apply  {
                    setMode(ResetCommand.ResetType.HARD)
                }.call()

                val mergeMsgFile = File("$gitDirPath/${context.getString(R.string.git_merge_msg_path)}")
                if (mergeMsgFile.exists()) {
                    mergeMsgFile.delete()
                }
                if (mergeHeadFile.exists()) {
                    mergeHeadFile.delete()
                }
            }

            log(LogType.AbortMerge, "Merge successful")
        } catch (e: IOException) {
            log(context, LogType.AbortMerge, e)
        } catch (e: GitAPIException) {
            log(context, LogType.AbortMerge, e)
        }
    }

    fun readGitignore(gitDirPath: String): String {
        if (!File("$gitDirPath/${context.getString(R.string.gitignore_path)}").exists()) return ""

        val gitignoreFile = File(gitDirPath, context.getString(R.string.gitignore_path))
        return gitignoreFile.readText()
    }

    fun writeGitignore(gitDirPath: String, gitignoreString: String) {
        val gitignoreFile = File(gitDirPath, context.getString(R.string.gitignore_path))
        if (!gitignoreFile.exists()) gitignoreFile.createNewFile()
        try {
            FileWriter(gitignoreFile, false).use { writer ->
                writer.write(gitignoreString)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun readGitInfoExclude(gitDirPath: String): String {
        if (!File("$gitDirPath/${context.getString(R.string.git_info_exclude_path)}").exists()) return ""

        val gitignoreFile = File(gitDirPath, context.getString(R.string.git_info_exclude_path))
        return gitignoreFile.readText()
    }

    fun writeGitInfoExclude(gitDirPath: String, gitignoreString: String) {
        val gitignoreFile = File(gitDirPath, context.getString(R.string.git_info_exclude_path))
        val parentDir = gitignoreFile.parentFile
        if (parentDir != null) {
            if (!parentDir.exists()) {
                parentDir.mkdirs()
            }
        }
        if (!gitignoreFile.exists()) gitignoreFile.createNewFile()

        try {
            FileWriter(gitignoreFile, false).use { writer ->
                writer.write(gitignoreString)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun closeRepo(repo: FileRepository) {
        repo.close()
        val lockFile = File(repo.directory, context.getString(R.string.git_lock_path))
        if (lockFile.exists()) lockFile.delete()
    }
}
