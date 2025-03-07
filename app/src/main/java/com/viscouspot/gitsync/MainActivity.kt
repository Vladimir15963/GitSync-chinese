package com.viscouspot.gitsync

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.viscouspot.gitsync.ui.NDSpinner
import com.viscouspot.gitsync.ui.RecyclerViewEmptySupport
import com.viscouspot.gitsync.ui.adapter.ApplicationListAdapter
import com.viscouspot.gitsync.ui.adapter.Commit
import com.viscouspot.gitsync.ui.adapter.RecentCommitsAdapter
import com.viscouspot.gitsync.ui.adapter.SpinnerIconPrefixAdapter
import com.viscouspot.gitsync.ui.dialog.ApplicationSelectDialog
import com.viscouspot.gitsync.ui.dialog.AuthDialog
import com.viscouspot.gitsync.ui.dialog.BaseDialog
import com.viscouspot.gitsync.ui.dialog.ManualSyncDialog
import com.viscouspot.gitsync.ui.dialog.MergeConflictDialog
import com.viscouspot.gitsync.ui.dialog.ProgressDialog
import com.viscouspot.gitsync.ui.dialog.SettingsDialog
import com.viscouspot.gitsync.ui.fragment.CloneRepoFragment
import com.viscouspot.gitsync.util.GitManager
import com.viscouspot.gitsync.util.Helper
import com.viscouspot.gitsync.util.Helper.makeToast
import com.viscouspot.gitsync.util.Helper.networkRequired
import com.viscouspot.gitsync.util.Helper.showContributeDialog
import com.viscouspot.gitsync.util.LogType
import com.viscouspot.gitsync.util.Logger.log
import com.viscouspot.gitsync.util.OnboardingController
import com.viscouspot.gitsync.util.RepoManager
import com.viscouspot.gitsync.util.SettingsManager
import com.viscouspot.gitsync.util.provider.GitProviderManager
import com.viscouspot.gitsync.util.rightDrawable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var applicationObserverMax: ConstraintSet
    private lateinit var applicationObserverMin: ConstraintSet

    private lateinit var gitManager: GitManager
    private lateinit var settingsManager: SettingsManager
    private lateinit var repoManager: RepoManager
    private lateinit var onboardingController: OnboardingController
    private lateinit var cloneRepoFragment: CloneRepoFragment

    private lateinit var gitRepoSpinner: Spinner
    private lateinit var repoSpinnerAdapter: ArrayAdapter<String>
    private lateinit var removeRepoButton: MaterialButton
    private lateinit var renameRepoButton: MaterialButton
    private lateinit var addRepoButton: MaterialButton
    private lateinit var repoActionsToggleButton: MaterialButton

    private val recentCommits: MutableList<Commit> = mutableListOf()
    private lateinit var recentCommitsRecycler: RecyclerViewEmptySupport
    private lateinit var recentCommitsAdapter: RecentCommitsAdapter

    private var mergeConflictDialog: Dialog? = null

    private lateinit var syncButton: MaterialButton
    private lateinit var syncMenuButton: MaterialButton
    private lateinit var syncOptionAdapter: SpinnerIconPrefixAdapter
    private lateinit var syncMenuSpinner: NDSpinner

    private lateinit var settingsButton: MaterialButton
    private lateinit var syncMessageButton: MaterialButton

    private lateinit var gitRepoName: EditText
    private lateinit var cloneRepoButton: MaterialButton
    private lateinit var gitAuthButton: MaterialButton

    private lateinit var gitDirPath: TextView
    private lateinit var deselectDirButton: MaterialButton
    private lateinit var selectDirButton: MaterialButton

    private lateinit var applicationObserverPanel: ConstraintLayout
    private lateinit var applicationObserverSwitch: Switch

    private lateinit var selectApplication: MaterialButton

    private val applicationList: MutableList<Drawable> = mutableListOf()
    private lateinit var applicationRecycler: RecyclerView
    private lateinit var applicationListAdapter: ApplicationListAdapter

    private lateinit var syncAppOpened: Switch
    private lateinit var syncAppClosed: Switch

    private var onStoragePermissionGranted: (() -> Unit)? = null
    private var requestLegacyStoragePermission: ActivityResultLauncher<Array<String>>? = null
    private var requestStoragePermission: ActivityResultLauncher<Intent>? = null

    private lateinit var authDialog: Dialog
    private var prominentDisclosure: BaseDialog? = null
    private var applicationSelectDialog: Dialog? = null

    private var requestedPermission = false

    private lateinit var syncOptionIconMap: Map<String, Int>
    private lateinit var syncOptionFnMap: Map<String, () -> Unit>
    private lateinit var baseSyncOptionIconMap: Map<String, Int>

    companion object {
        const val REFRESH = "REFRESH"
        const val MANUAL_SYNC = "MANUAL_SYNC"
        const val MERGE_COMPLETE = "MERGE_COMPLETE"
    }

    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                REFRESH -> refreshRecentCommits()
                MERGE_COMPLETE -> {
                    mergeConflictDialog?.dismiss()
                    refreshRecentCommits()
                }
            }
        }
    }

    private val dirSelectionLauncher = Helper.getDirSelectionLauncher(this, this, ::dirSelectionCallback)

    private fun dirSelectionCallback(dirUri: Uri?) {
        if (dirUri == null) {
            makeToast(this, getString(R.string.inaccessible_directory_message), Toast.LENGTH_LONG)
            return
        }

        settingsManager.setGitDirUri(dirUri.toString())

        val recentCommitsSize = recentCommits.size
        recentCommits.clear()
        recentCommitsAdapter.notifyItemRangeRemoved(0, recentCommitsSize)

        gitDirPath.text = Helper.getPathFromUri(this, dirUri)
        refreshGitRepo()

        settingsManager.setOnboardingStep(4)
        onboardingController.dismissAll()
        onboardingController.show()
    }

    private val requestNotificationPermission = this.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is EditText) {
                val outRect = Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    v.clearFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                }
            }
        }

        return super.dispatchTouchEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == MANUAL_SYNC) {
            ManualSyncDialog(this, settingsManager, gitManager, ::refreshRecentCommits).show()
            return
        }
        
        val uri = intent.data ?: return

        log(LogType.GithubOAuthFlow, "Flow Ended")

        val gitManager = GitProviderManager.getManager(this, settingsManager)
        gitManager.getOAuthCredentials(uri, ::setGitCredentials)
    }

    fun setGitCredentials(username: String?, token: String?) {
        if (token == null) {
            return
        }

        if (username == null) {
            log(LogType.GithubAuthCredentials, "SSH Key Received")
            settingsManager.setGitSshPrivateKey(token)
        } else {
            log(LogType.GithubAuthCredentials, "Username and Token Received")
            settingsManager.setGitAuthCredentials(username, token)
        }

        if (!cloneRepoFragment.isAdded && !supportFragmentManager.isStateSaved) {
            cloneRepoFragment.show(supportFragmentManager, getString(R.string.clone_repo_title))
        }

        settingsManager.setOnboardingStep(3)
        onboardingController.dismissAll()

        refreshAuthButton()
    }

    private fun setRecyclerViewHeight(recyclerView: RecyclerView) {
        val adapter = recyclerView.adapter ?: return

        val viewHolder = adapter.createViewHolder(recyclerView, adapter.getItemViewType(0))
        viewHolder.itemView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)

        val itemHeight = (viewHolder.itemView.layoutParams as ViewGroup.MarginLayoutParams).topMargin + viewHolder.itemView.measuredHeight

        recyclerView.layoutParams.height = itemHeight * 3
        recyclerView.requestLayout()
    }

    override fun onPause() {
        super.onPause()

        if (settingsManager.getOnboardingStep() != 0 && !onboardingController.hasSkipped) {
            onboardingController.dismissAll()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            unregisterReceiver(broadcastReceiver)
        } catch (_: Exception) { }
    }

    override fun onResume() {
        super.onResume()

        if (requestedPermission) {
            requestedPermission = false
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                settingsManager.setSyncMessageEnabled(true)
            }

            if (checkAccessibilityPermission()) {
                settingsManager.setApplicationObserverEnabled(true)
            }
        } else {
            if (settingsManager.getOnboardingStep() != -1 && !authDialog.isShowing && prominentDisclosure?.isShowing != true) {
                onboardingController.show()
                return
            }

            val hasAllFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            if (!hasAllFilesAccess) {
                if (onboardingController.showAllFilesAccessOrNext(true)) return
            }
        }

        refreshAll()
    }

    fun updateRepoButtons() {
        val repoIndex = repoManager.getRepoIndex()
        runOnUiThread {
            renameRepoButton.visibility = View.GONE
            removeRepoButton.visibility = View.GONE
            addRepoButton.visibility = View.GONE
            repoActionsToggleButton.visibility = View.GONE

            repoSpinnerAdapter.clear()
            repoSpinnerAdapter.addAll(repoManager.getRepoNames())
            repoSpinnerAdapter.notifyDataSetChanged()
            gitRepoSpinner.post{
                gitRepoSpinner.dropDownWidth = gitRepoSpinner.width
            }

            gitRepoSpinner.setSelection(repoIndex)

            if (repoManager.getRepoNames().size < 2) {
                gitRepoSpinner.visibility = View.GONE
                addRepoButton.visibility = View.VISIBLE
            } else {
                gitRepoSpinner.visibility = View.VISIBLE
                repoActionsToggleButton.visibility = View.VISIBLE
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        baseSyncOptionIconMap = mapOf(
            Pair(getString(R.string.sync_now), R.drawable.pull),
            Pair(getString(R.string.manual_sync), R.drawable.manual_sync),
            Pair(getString(R.string.pull_changes), R.drawable.pull_changes),
            Pair(getString(R.string.force_push), R.drawable.force_push),
            Pair(getString(R.string.force_pull), R.drawable.force_pull),
        )

        syncOptionIconMap = baseSyncOptionIconMap

        syncOptionFnMap = mapOf(
            Pair(getString(R.string.sync_now)) {
                val forceSyncIntent = Intent(this, GitSyncService::class.java)
                forceSyncIntent.setAction(GitSyncService.FORCE_SYNC)
                forceSyncIntent.putExtra("repoIndex", repoManager.getRepoIndex())
                startService(forceSyncIntent)
                return@Pair
            },
            Pair(getString(R.string.force_push)) {
                showConfirmForcePushPullDialog(true)
            },
            Pair(getString(R.string.force_pull)) {
                showConfirmForcePushPullDialog(false)
            },
            Pair(getString(R.string.pull_changes)) {
                val gitDirUri = settingsManager.getGitDirUri()
                if (gitDirUri == null) {
                    runOnUiThread {
                        log(LogType.Sync, "Repository Not Found")
                        makeToast(
                            applicationContext,
                            getString(R.string.repository_not_found),
                            Toast.LENGTH_LONG
                        )
                    }
                    return@Pair
                }
                val job = CoroutineScope(Dispatchers.Default).launch {
                    val result = gitManager.downloadChanges(
                        gitDirUri,
                        {
                            networkRequired(applicationContext)
                        },
                        null
                    )

                    if (result == false) {
                        makeToast(applicationContext, getString(R.string.pull_failed))
                    }
                }

                job.invokeOnCompletion {
                    runOnUiThread {
                        refreshRecentCommits()
                    }
                }

            },
            Pair(getString(R.string.manual_sync)) {
                showContributeDialog(this, repoManager) {
                    ManualSyncDialog(this, settingsManager, gitManager, ::refreshRecentCommits).show()
                }
            },
        )
        
        repoManager = RepoManager(this)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        gitRepoSpinner = findViewById(R.id.gitRepoSpinner)
        removeRepoButton = findViewById(R.id.removeRepoButton)
        renameRepoButton = findViewById(R.id.renameRepoButton)
        addRepoButton = findViewById(R.id.addRepoButton)
        repoActionsToggleButton = findViewById(R.id.repoActionsToggleButton)

        repoSpinnerAdapter =  ArrayAdapter<String>(
            this,
            R.layout.item_spinner_compact
        )

        gitRepoSpinner.adapter = repoSpinnerAdapter

        updateRepoButtons()

        var initialGitRepoSpinner = false
        gitRepoSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (!initialGitRepoSpinner) {
                    initialGitRepoSpinner = true
                    return
                }

                repoManager.setRepoIndex(position)
                updateRepoButtons()
                refreshAll()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        repoActionsToggleButton.setOnClickListener {
            renameRepoButton.visibility = View.VISIBLE
            if (repoManager.getRepoNames().size > 1) {
                removeRepoButton.visibility = View.VISIBLE
            }
            addRepoButton.visibility = View.VISIBLE
            repoActionsToggleButton.visibility = View.GONE
        }

        removeRepoButton.setOnClickListener {
            BaseDialog(this)
                .setTitle(getString(R.string.confirm_container_delete))
                .setMessage(
                    getString(
                        R.string.confirm_container_delete_msg,
                        repoManager.getRepoNames().elementAt(repoManager.getRepoIndex())
                    )
                )
                .setCancelable(1)
                .setPositiveButton(android.R.string.cancel) { _, _ -> }
                .setNegativeButton(R.string.confirm) { _, _ ->
                    settingsManager.clearAll()
                    val repoNames = repoManager.getRepoNames().toMutableList()
                    repoNames.removeAt(repoManager.getRepoIndex())

                    repoManager.setRepoNames(repoNames)
                    if (repoManager.getRepoIndex() >= repoNames.size) {
                        repoManager.setRepoIndex(repoNames.size - 1)
                    }

                    updateRepoButtons()
                    refreshAll()
                }
                .show()
        }

        renameRepoButton.setOnClickListener {
            val keyInput = LayoutInflater.from(this).inflate(R.layout.edittext_key, null) as ConstraintLayout
            val input = keyInput.findViewById<EditText>(R.id.input)
            input.hint = getString(R.string.default_container_name)
            input.setText(repoManager.getRepoNames().elementAt(repoManager.getRepoIndex()))
            BaseDialog(this)
                .setTitle(getString(R.string.rename_container))
                .setCancelable(1)
                .setView(keyInput)
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .setPositiveButton(R.string.rename, null)
                .apply {
                    setOnShowListener {
                        getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener listener@{
                            if (input.text.isEmpty()) return@listener

                            val repoNames = repoManager.getRepoNames().toMutableList()
                            SettingsManager.renameSettingsPref(
                                applicationContext,
                                "${SettingsManager.PREFIX}${repoNames[repoManager.getRepoIndex()]}",
                                "${SettingsManager.PREFIX}${input.text}",
                            )
                            repoNames[repoManager.getRepoIndex()] = input.text.toString()

                            repoManager.setRepoNames(repoNames)

                            updateRepoButtons()
                            refreshAll()
                            dismiss()
                        }
                    }
                }
                .show()
        }

        addRepoButton.setOnClickListener {
            showContributeDialog(this, repoManager) {
                val keyInput = LayoutInflater.from(this).inflate(R.layout.edittext_key, null) as ConstraintLayout
                val input = keyInput.findViewById<EditText>(R.id.input)
                input.hint = getString(R.string.default_container_name)
                BaseDialog(this)
                    .setTitle(getString(R.string.add_container))
                    .setMessage(getString(R.string.add_container_msg))
                    .setCancelable(1)
                    .setView(keyInput)
                    .setPositiveButton(R.string.add) { _, _ -> }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> }
                    .apply {
                        setOnShowListener {
                            getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener listener@{
                                val repoNames = repoManager.getRepoNames().toMutableList()
                                if (repoNames.contains(input.text.toString())) {
                                    input.setText("${input.text}2")
                                    return@listener
                                }

                                repoNames.add(input.text.toString())

                                repoManager.setRepoNames(repoNames)
                                repoManager.setRepoIndex(repoNames.indexOf(input.text.toString()))

                                updateRepoButtons()
                                refreshAll()
                                dismiss()
                            }
                        }
                    }
                    .show()
            }
        }

        System.setProperty("org.eclipse.jgit.util.Debug", "true")
        System.setProperty("org.apache.sshd.common.util.logging.level", "DEBUG")

        Thread.setDefaultUncaughtExceptionHandler { _, paramThrowable ->
            log(this, LogType.Global, paramThrowable)
        }

        settingsManager = SettingsManager(this)
        settingsManager.runMigrations()

        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        if (System.currentTimeMillis() - packageInfo.firstInstallTime >= 30L * 24 * 60 * 60 * 1000) {
            showContributeDialog(this, repoManager) {}
        }

        requestLegacyStoragePermission = this.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { isGrantedMap ->
            if (isGrantedMap.values.all { it }) {
                onStoragePermissionGranted?.invoke()
            }
        }

        requestStoragePermission = this.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val hasPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermissions) {
                onStoragePermissionGranted?.invoke()
            }
        }

        val bManager = LocalBroadcastManager.getInstance(this)
        val intentFilter = IntentFilter()
        intentFilter.addAction(REFRESH)
        intentFilter.addAction(MERGE_COMPLETE)
        bManager.registerReceiver(broadcastReceiver, intentFilter)

        window.statusBarColor = ContextCompat.getColor(this, R.color.app_bg)

        gitManager = GitManager(this, settingsManager)

        recentCommitsRecycler = findViewById(R.id.recentCommitsRecycler)

        recentCommitsAdapter = RecentCommitsAdapter(this, recentCommits, ::openMergeConflictDialog)

        syncButton = findViewById(R.id.syncButton)
        syncMenuButton = findViewById(R.id.syncMenuButton)
        syncMenuSpinner = findViewById(R.id.syncMenuSpinner)

        settingsButton = findViewById(R.id.settingsButton)
        syncMessageButton = findViewById(R.id.syncMessageButton)

        gitRepoName = findViewById(R.id.gitRepoName)
        cloneRepoButton = findViewById(R.id.cloneRepoButton)
        gitAuthButton = findViewById(R.id.gitAuthButton)

        gitDirPath = findViewById(R.id.gitDirPath)
        deselectDirButton = findViewById(R.id.deselectDirButton)
        selectDirButton = findViewById(R.id.selectDirButton)

        applicationObserverPanel = findViewById(R.id.applicationObserverPanel)
        applicationObserverSwitch = applicationObserverPanel.findViewById(R.id.enableApplicationObserver)

        selectApplication = findViewById(R.id.selectApplication)
        applicationRecycler = findViewById(R.id.applicationRecycler)
        applicationListAdapter = ApplicationListAdapter(applicationList)
        syncAppOpened = findViewById(R.id.syncAppOpened)
        syncAppClosed = findViewById(R.id.syncAppClosed)

        applicationObserverMax = ConstraintSet().apply { clone(applicationContext, R.layout.application_observer_max) }
        applicationObserverMin = ConstraintSet().apply { clone(applicationContext, R.layout.application_observer_min) }

        recentCommitsRecycler.adapter = recentCommitsAdapter
        applicationRecycler.adapter = applicationListAdapter

        authDialog = AuthDialog(this, settingsManager, ::setGitCredentials)
        cloneRepoFragment = CloneRepoFragment(settingsManager, gitManager, ::dirSelectionCallback)
        onboardingController = OnboardingController(this, this, settingsManager, authDialog, cloneRepoFragment, ::updateApplicationObserver, ::checkAndRequestNotificationPermission, ::checkAndRequestStoragePermission)

        setRecyclerViewHeight(recentCommitsRecycler)

        val emptyCommitsView = findViewById<TextView>(R.id.emptyCommitsView)
        recentCommitsRecycler.setEmptyView(emptyCommitsView)

        syncOptionAdapter = SpinnerIconPrefixAdapter(this, syncOptionIconMap.filter { (key, _) -> settingsManager.getLastSyncMethod() != key }.toList())

        syncMenuSpinner.adapter = syncOptionAdapter
        syncMenuSpinner.post{
            syncMenuSpinner.dropDownWidth = syncMenuSpinner.width
        }

        var initial = false
        syncMenuSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (!initial) {
                    initial = true
                    return
                }

                val filteredSyncOptions = syncOptionFnMap.filter { (key, _) -> settingsManager.getLastSyncMethod() != key }
                val syncOption = filteredSyncOptions.keys.toList()[position]

                settingsManager.setLastSyncMethod(syncOption)
                filteredSyncOptions[syncOption]?.invoke()
                updateSyncOptions()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        syncMenuButton.setOnClickListener {
            syncMenuSpinner.performClick()
        }

        settingsButton.setOnClickListener {
            openSettingsDialog()
        }

        syncMessageButton.setOnClickListener {
            val syncMessageEnabled = settingsManager.getSyncMessageEnabled()

            if (!syncMessageEnabled) {
                checkAndRequestNotificationPermission {
                    settingsManager.setSyncMessageEnabled(true)
                    syncMessageButton.setIconResource(R.drawable.notify)
                    syncMessageButton.setIconTintResource(R.color.auth_green)
                }
            } else {
                settingsManager.setSyncMessageEnabled(false)
                syncMessageButton.setIconResource(R.drawable.notify_off)
                syncMessageButton.setIconTintResource(R.color.primary_light)
            }
        }

        gitAuthButton.setOnClickListener {
            authDialog.show()
        }

        deselectDirButton.setOnClickListener {
            settingsManager.setGitDirUri("")

            gitDirPath.text = getString(R.string.git_dir_path_hint)
            refreshGitRepo()
            val recentCommitsSize = recentCommits.size
            recentCommits.clear()
            recentCommitsAdapter.notifyItemRangeRemoved(0, recentCommitsSize)
        }

        selectDirButton.setOnClickListener {
            dirSelectionLauncher.launch(null)
        }

        (if (settingsManager.getApplicationObserverEnabled()) applicationObserverMax else applicationObserverMin).applyTo(applicationObserverPanel)
        updateApplicationObserverSwitch()

        applicationObserverSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateApplicationObserver(isChecked)
        }

        selectApplication.setOnClickListener {
            showApplicationSelectDialog()
        }

        syncAppOpened.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.setSyncOnAppOpened(isChecked)
        }

        syncAppClosed.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.setSyncOnAppClosed(isChecked)
        }

        if (intent.action == MANUAL_SYNC) {
            ManualSyncDialog(this, settingsManager, gitManager, ::refreshRecentCommits).show()
        }
    }

    private fun showConfirmForcePushPullDialog(push: Boolean) {
        val stringRes = if (push) listOf(
            R.string.confirm_force_push,
            R.string.confirm_force_push_msg,
            R.string.force_push,
            R.string.force_pushing,
        ) else listOf(
            R.string.confirm_force_pull,
            R.string.confirm_force_pull_msg,
            R.string.force_pull,
            R.string.force_pulling,
        )

        BaseDialog(this)
            .setTitle(getString(stringRes[0]))
            .setMessage(getString(stringRes[1]))
            .setCancelable(1)
            .setPositiveButton(android.R.string.cancel) { _, _ -> }
            .setNegativeButton(stringRes[2]) { _, _ ->
                val progressBar =
                    ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100
                        isIndeterminate = true
                        progressTintList = ColorStateList.valueOf(
                            ContextCompat.getColor(
                                context,
                                R.color.auth_green
                            )
                        )
                        setPadding(
                            resources.getDimension(R.dimen.space_lg).toInt(),
                            0,
                            resources.getDimension(R.dimen.space_lg).toInt(),
                            0
                        )
                    }
                val cloneDialog: ProgressDialog = ProgressDialog(this)
                    .setTitle(getString(stringRes[3]))
                    .setMessage(getString(R.string.force_push_pull_message))
                    .setCancelable(1)
                    .setView(progressBar)
                cloneDialog.show()
                CoroutineScope(Dispatchers.IO).launch {
                    val gitDirUri = settingsManager.getGitDirUri()
                    if (gitDirUri == null) {
                        runOnUiThread {
                            log(LogType.Sync, "Repository Not Found")
                            makeToast(
                                applicationContext,
                                getString(R.string.repository_not_found),
                                Toast.LENGTH_LONG
                            )
                            cloneDialog.dismiss()
                        }
                        return@launch
                    }

                    if (push) gitManager.forcePush(gitDirUri) else gitManager.forcePull(gitDirUri)

                    runOnUiThread {
                        refreshRecentCommits()
                        cloneDialog.dismiss()
                    }
                }
            }
            .show()
    }

    private fun updateApplicationObserver(isChecked: Boolean) {
        runOnUiThread {
            (if (isChecked) applicationObserverMax else applicationObserverMin).applyTo(
                applicationObserverPanel
            )
        }
        if (isChecked) {
            updateApplicationObserverSwitch(true)
            if (!checkAccessibilityPermission()) {
                runOnUiThread {
                    applicationObserverSwitch.isChecked = false
                    syncAppOpened.isChecked = false
                    syncAppClosed.isChecked = false
                    updateApplicationObserverSwitch(false)
                    applicationObserverMin.applyTo(applicationObserverPanel)
                    displayProminentDisclosure()
                }
            } else {
                settingsManager.setApplicationObserverEnabled(true)
                refreshSelectedApplications()
            }
        } else {
            updateApplicationObserverSwitch(false)
            settingsManager.setApplicationObserverEnabled(false)
        }
    }

    private fun openMergeConflictDialog() {
        if (mergeConflictDialog?.isShowing == true) mergeConflictDialog?.dismiss()

        mergeConflictDialog = MergeConflictDialog(this, repoManager.getRepoIndex(), settingsManager, gitManager, ::refreshRecentCommits)
        mergeConflictDialog?.show()
    }

    private fun updateApplicationObserverSwitch(upDown: Boolean = settingsManager.getApplicationObserverEnabled()) {
        runOnUiThread {
            applicationObserverSwitch.setCompoundDrawablesWithIntrinsicBounds(null,
                null,
                ContextCompat.getDrawable(
                    this,
                    if (upDown) R.drawable.angle_up else R.drawable.angle_down
                )
                    ?.apply {
                        setTint(
                            ContextCompat.getColor(
                                this@MainActivity,
                                if (checkAccessibilityPermission()) R.color.auth_green else R.color.text_secondary
                            )
                        )
                    },
                null
            )
        }
    }

    private fun openSettingsDialog() {
        val settingsDialog = SettingsDialog(this, repoManager, settingsManager, gitManager, gitDirPath.text.toString())
        settingsDialog.show()
    }

    private fun getDeviceApps(): List<String> {
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, PackageManager.GET_META_DATA)

        return apps.map {
            it.activityInfo.packageName
        }.sortedBy { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }
    }

    private fun showApplicationSelectDialog() {
        if (applicationSelectDialog?.isShowing == true) applicationSelectDialog?.dismiss()

        applicationSelectDialog = ApplicationSelectDialog(this, settingsManager, getDeviceApps(), ::refreshSelectedApplications)
        applicationSelectDialog?.show()
    }

    private fun updateSyncOptions() {
        runOnUiThread {
            val mainSyncMethodKey = syncOptionIconMap.keys.firstOrNull { it == settingsManager.getLastSyncMethod() } ?: syncOptionIconMap.keys.first()
            syncOptionAdapter.clear()
            syncOptionAdapter.addAll(syncOptionIconMap.filter { (key, _) -> mainSyncMethodKey != key }.toList())
            syncOptionAdapter.notifyDataSetChanged()

            syncButton.text = mainSyncMethodKey
            syncButton.icon = ContextCompat.getDrawable(this, syncOptionIconMap[mainSyncMethodKey] ?: R.drawable.pull)?.mutate()
            syncButton.setOnClickListener {
                val syncFn = syncOptionFnMap[mainSyncMethodKey] ?: {}
                syncFn.invoke()
            }
        }
    }

    private fun refreshAll() {
        CoroutineScope(Dispatchers.Default).launch {
            updateSyncOptions()

            refreshRecentCommits()

            runOnUiThread {
                if (settingsManager.getSyncMessageEnabled()) {
                    settingsManager.setSyncMessageEnabled(false)
                    if (settingsManager.getOnboardingStep() != 0) {
                        checkAndRequestNotificationPermission {
                            settingsManager.setSyncMessageEnabled(true)
                            syncMessageButton.setIconResource(R.drawable.notify)
                            syncMessageButton.setIconTintResource(R.color.auth_green)
                        }
                    }
                } else {
                    syncMessageButton.setIconResource(R.drawable.notify_off)
                    syncMessageButton.setIconTintResource(R.color.primary_light)
                }
            }

            refreshAuthButton()
            refreshGitRepo()

            runOnUiThread {

                val gitDirUri = settingsManager.getGitDirUri()
                if (gitDirUri == null) {
                    gitDirPath.text = getString(R.string.git_dir_path_hint)
                } else {
                    gitDirPath.text = Helper.getPathFromUri(applicationContext, gitDirUri)
                }
            }

            val applicationObserverEnabled = settingsManager.getApplicationObserverEnabled()
            updateApplicationObserver(applicationObserverEnabled)

            runOnUiThread {
                syncAppOpened.isChecked = settingsManager.getSyncOnAppOpened()
                syncAppClosed.isChecked = settingsManager.getSyncOnAppClosed()
            }
        }
    }

    private fun refreshSelectedApplications() {
        runOnUiThread {
            if (settingsManager.getApplicationPackages().isEmpty()) {
                syncAppOpened.isEnabled = false
                syncAppOpened.isChecked = false
                syncAppClosed.isEnabled = false
                syncAppClosed.isChecked = false
            } else {
                syncAppOpened.isEnabled = true
                syncAppClosed.isEnabled = true
            }

            val packageNames = settingsManager.getApplicationPackages()
            if (packageNames.isNotEmpty()) {
                when (packageNames.size) {
                    1 -> {
                        selectApplication.text = packageManager.getApplicationLabel(
                            packageManager.getApplicationInfo(
                                packageNames.elementAt(0),
                                0
                            )
                        ).toString()
                        selectApplication.icon =
                            packageManager.getApplicationIcon(packageNames.elementAt(0))
                        selectApplication.iconTintMode = PorterDuff.Mode.MULTIPLY
                        selectApplication.iconTint = ContextCompat.getColorStateList(this, android.R.color.white)

                        applicationRecycler.visibility = View.GONE
                    }

                    else -> {
                        selectApplication.text = getString(R.string.multiple_application_selected).format(if (packageNames.size < 5) packageNames.size else getString(R.string.lg_3_apps_selected_text))
                        selectApplication.icon = null

                        applicationRecycler.visibility = View.VISIBLE
                        val iconList = packageNames.map { packageManager.getApplicationIcon(it) }
                        val prevSize = applicationList.size
                        applicationList.clear()
                        applicationListAdapter.notifyItemRangeRemoved(0, prevSize)
                        applicationList.addAll(iconList)
                        applicationListAdapter.notifyItemRangeInserted(0, iconList.size)
                    }
                }
            } else {
                selectApplication.text = getString(R.string.application_not_set)
                selectApplication.setIconResource(R.drawable.circle_plus)
                selectApplication.setIconTintResource(R.color.primary_light)
                selectApplication.iconTintMode = PorterDuff.Mode.SRC_IN

                applicationRecycler.visibility = View.GONE
            }
        }
    }

    private fun refreshRecentCommits() {
        val gitDirUri = settingsManager.getGitDirUri()
        gitDirUri?.let {
            val newRecentCommits = gitManager.getRecentCommits(Helper.getPathFromUri(this, it))
            if (recentCommits.map { commit -> commit.reference } != newRecentCommits.map { commit -> commit.reference }) {
                runOnUiThread {
                    val recentCommitsSize = recentCommits.size
                    recentCommits.clear()
                    recentCommitsAdapter.notifyItemRangeRemoved(0, recentCommitsSize)
                }
                if (newRecentCommits.isNotEmpty()) {
                    runOnUiThread {
                        recentCommits.addAll(0, newRecentCommits)
                        recentCommitsAdapter.notifyItemRangeInserted(0, newRecentCommits.size)
                        recentCommitsRecycler.smoothScrollToPosition(0)
                    }
                }
            }
        }

        if (gitManager.getConflicting(settingsManager.getGitDirUri()).isNotEmpty()) {
            runOnUiThread {
                syncOptionIconMap = baseSyncOptionIconMap.filter { listOf(getString(R.string.force_push), getString(R.string.force_pull)).contains(it.key) }

                if (recentCommits.firstOrNull { it.reference == RecentCommitsAdapter.MERGE_CONFLICT } == null) {
                    recentCommits.add(0, Commit("", "", 0L, RecentCommitsAdapter.MERGE_CONFLICT, 0, 0))
                }
                recentCommitsAdapter.notifyItemInserted(0)
                recentCommitsRecycler.smoothScrollToPosition(0)
            }
        } else {
            syncOptionIconMap = baseSyncOptionIconMap
            runOnUiThread {
                syncButton.isEnabled = true
            }
        }

        updateSyncOptions()
    }

    private fun refreshGitRepo() {
        var repoName = ""
        val gitDirUri = settingsManager.getGitDirUri()

        gitDirUri?.let {
            val gitConfigFile = File("${Helper.getPathFromUri(this, it)}/${getString(R.string.git_config_path)}")
            if (gitConfigFile.exists()) {
                val fileContents = gitConfigFile.readText()

                val gitConfigUrlRegex = "url = (.*?)\\n".toRegex()
                val gitConfigUrlResult = gitConfigUrlRegex.find(fileContents)
                val url = gitConfigUrlResult?.groups?.get(1)?.value

                val gitRepoNameRegex = ".*/([^/]+?)(\\.git)?$".toRegex()
                val gitRepoNameResult = gitRepoNameRegex.find(url.toString())
                repoName = gitRepoNameResult?.groups?.get(1)?.value ?: ""
            }
        }
        runOnUiThread {
            if (repoName == "") {
                gitRepoName.setText(getString(R.string.repo_not_found))
                gitRepoName.isEnabled = false

                cloneRepoButton.visibility = View.VISIBLE
                deselectDirButton.visibility = View.GONE
                cloneRepoButton.setOnClickListener {
                    if (!cloneRepoFragment.isAdded) {
                        cloneRepoFragment.show(
                            supportFragmentManager,
                            getString(R.string.clone_repo)
                        )
                    }
                }

                applicationObserverSwitch.isChecked = false
                applicationObserverSwitch.isEnabled = false

                if (gitDirPath.text.isEmpty()) {
                    gitRepoName.rightDrawable(null)
                    gitRepoName.compoundDrawablePadding = 0
                    return@runOnUiThread
                }

                gitRepoName.rightDrawable(R.drawable.circle_xmark)
                TextViewCompat.setCompoundDrawableTintList(
                    gitRepoName,
                    ContextCompat.getColorStateList(this, R.color.auth_red)
                )
                gitRepoName.compoundDrawablePadding =
                    (4 * resources.displayMetrics.density + 0.5f).toInt()

                val recentCommitsSize = recentCommits.size
                recentCommits.clear()
                recentCommitsAdapter.notifyItemRangeRemoved(0, recentCommitsSize)

                return@runOnUiThread
            }

            gitRepoName.setText(repoName)
            gitRepoName.isEnabled = true
            gitRepoName.rightDrawable(R.drawable.circle_check)
            TextViewCompat.setCompoundDrawableTintList(
                gitRepoName,
                ContextCompat.getColorStateList(this, R.color.auth_green)
            )
            gitRepoName.compoundDrawablePadding =
                (4 * resources.displayMetrics.density + 0.5f).toInt()

            cloneRepoButton.visibility = View.GONE
            deselectDirButton.visibility = View.VISIBLE

            applicationObserverSwitch.isEnabled = true
        }

        refreshRecentCommits()
    }

    private fun refreshAuthButton() {
        if (settingsManager.getGitAuthCredentials().second != "" || settingsManager.getGitSshPrivateKey() != "") {
            runOnUiThread {
                gitAuthButton.icon = ContextCompat.getDrawable(this, R.drawable.circle_check)
                gitAuthButton.setIconTintResource(R.color.auth_green)

                selectDirButton.isEnabled = true
                cloneRepoButton.isEnabled = true
            }
            return
        }

        runOnUiThread {
            gitAuthButton.icon = ContextCompat.getDrawable(this, R.drawable.circle_xmark)
            gitAuthButton.setIconTintResource(R.color.auth_red)

            selectDirButton.isEnabled = false
            cloneRepoButton.isEnabled = false
        }
    }

    private fun checkAccessibilityPermission(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)

        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
                    it.resolveInfo.serviceInfo.name == GitSyncAccessibilityService::class.java.name
        }
    }

    private fun displayProminentDisclosure() {
        prominentDisclosure?.dismiss()

        prominentDisclosure = BaseDialog(this)
            .setTitle(getString(R.string.accessibility_service_disclosure_title))
            .setMessage(getString(R.string.accessibility_service_disclosure_message))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                requestAccessibilityPermission()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }

        prominentDisclosure?.show()
    }

    private fun requestAccessibilityPermission() {
        val openSettings = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        openSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
        requestedPermission = true
        settingsManager.setOnboardingStep(-1)
        startActivity(openSettings)
        makeToast(this, getString(R.string.enable_accessibility_service), Toast.LENGTH_LONG)
    }

    private fun checkAndRequestNotificationPermission(onGranted: (() -> Unit)? = null) {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            onGranted?.invoke()
            return
        }

        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        } else {
            Intent().apply {
                action = "android.settings.APP_NOTIFICATION_SETTINGS"
                putExtra("app_package", packageName)
            }
        }

        requestedPermission = true
        requestNotificationPermission.launch(intent)
    }

    private fun checkAndRequestStoragePermission(onGranted: (() -> Unit)? = null) {
        val hasPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED


        if (hasPermissions) {
            onGranted?.invoke()
            return
        }

        onStoragePermissionGranted = onGranted
        requestedPermission = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uri = Uri.fromParts("package", packageName, null)
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)
            intent.data = Uri.fromParts("package", packageName, null)
            requestStoragePermission?.launch(intent)
        } else {
            requestLegacyStoragePermission?.launch(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }
}
