package su.xash.engine.ui.downloader

import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import su.xash.engine.R
import su.xash.engine.databinding.FragmentDownloadPanelBinding
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

class DownloadPanelFragment : Fragment() {

    private var _binding: FragmentDownloadPanelBinding? = null
    private val binding get() = _binding!!
    private var adapter: DownloadAdapter? = null
    
    private var downloadJob: Job? = null
    private val isPaused = AtomicBoolean(false)
    
    private val isOperationRunning = AtomicBoolean(false)
    private var isDownloading = false
    private var isExtracting = false
    private var isDeleting = false
    
    private var activeGameId: String? = null
    private val menuSourceSelectorId = View.generateViewId()

    enum class DownloadSource(val brandName: String, val speedResId: Int, val urlPattern: String) {
        GITLAB("GitLab", R.string.source_speed_fastest, "https://gitlab.com/steamdepot/goldsrc-valve/-/archive/{branch}/goldsrc-valve-{branch}.zip"),
        GITHUB_RELEASES("GitHub Releases", R.string.source_speed_fast, "https://github.com/steamdepot/goldsrc-valve/releases/download/uploads/{branch}.zip"),
        GITHUB_ARCHIVE("GitHub", R.string.source_speed_medium, "https://github.com/steamdepot/goldsrc-valve/archive/refs/heads/{branch}.zip"),
        ARCHIVE_ORG("archive.org", R.string.source_speed_slow, "https://archive.org/download/goldsrc-valve/{branch}.zip");

        fun getFormattedName(context: Context): String {
            return "$brandName (${context.getString(speedResId)})"
        }
    }

    private val prefs by lazy {
        requireContext().getSharedPreferences("downloader_settings", Context.MODE_PRIVATE)
    }

    private var selectedSource: DownloadSource
        get() {
            val name = prefs.getString("selected_source", DownloadSource.GITLAB.name) ?: DownloadSource.GITLAB.name
            return try { DownloadSource.valueOf(name) } catch (e: Exception) { DownloadSource.GITLAB }
        }
        set(value) {
            prefs.edit().putString("selected_source", value.name).apply()
        }

    private val gamesList = listOf(
        GameDownloadInfo("valve", "Half-Life", true, 278714959L, 6451926L, 529827940L, 9067684L),
        GameDownloadInfo("gearbox", "Opposing Force", true, 143197003L, 7402783L, 275065310L, 11200080L),
        GameDownloadInfo("cstrike", "Counter-Strike", true, 153860064L, 15096097L, 292340389L, 26459518L),
        GameDownloadInfo("bshift", "Blue Shift", true, 174107551L, 6839837L, 291836020L, 9896000L),
        GameDownloadInfo("tfc", "Team Fortress", false, 62585513L, 0L, 125934169L, 0L),
        GameDownloadInfo("czero", "Condition Zero", false, 227056798L, 0L, 415108359L, 0L),
        GameDownloadInfo("dmc", "Deathmatch Classic", false, 22995236L, 0L, 48239254L, 0L)
    )

    enum class ConflictStrategy { REPLACE, CLEAN_INSTALL, SKIP_EXISTING }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadPanelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cleanLegacyTempFiles()
        setupRecyclerView()
        setupControlButtons()
        setupBackPressedLogic()
        setupMenuProvider()
    }

    private fun cleanLegacyTempFiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                requireContext().cacheDir?.listFiles()?.forEach { file ->
                    if (file.name.endsWith("_temp.zip")) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = DownloadAdapter(gamesList) { game, isHdSelected ->
            if (isOperationRunning.get()) return@DownloadAdapter
            checkHalfLifeRequirement(game, isHdSelected)
        }
        binding.downloadRecyclerView.adapter = adapter
    }

    private fun setupControlButtons() {
        binding.btnPauseResume.setOnClickListener {
            if (isExtracting || isDeleting || !isOperationRunning.get()) return@setOnClickListener

            if (isPaused.get()) {
                isPaused.set(false)
                binding.btnPauseResume.setIconResource(R.drawable.ic_baseline_pause_24px)
            } else {
                isPaused.set(true)
                binding.btnPauseResume.setIconResource(R.drawable.ic_baseline_resume_24px)
            }
        }

        binding.btnCancelDownload.setOnClickListener {
            showCancelConfirmationDialog()
        }
    }

    private fun setupBackPressedLogic() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isOperationRunning.get()) {
                    showCancelConfirmationDialog()
                } else {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupMenuProvider() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menu.clear()
                val menuItem = menu.add(Menu.NONE, menuSourceSelectorId, Menu.NONE, R.string.settings_download_source)
                menuItem.setIcon(R.drawable.ic_baseline_cloud_24px)
                menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    menuSourceSelectorId -> {
                        showSourceSelectionDialog()
                        true
                    }
                    android.R.id.home -> {
                        if (isOperationRunning.get()) {
                            showCancelConfirmationDialog()
                            true
                        } else {
                            findNavController().navigateUp()
                            true
                        }
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun showSourceSelectionDialog() {
        val sources = DownloadSource.values()
        val displayItems = sources.map { it.getFormattedName(requireContext()) }.toTypedArray()
        val checkedItemIndex = sources.indexOf(selectedSource)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.dialog_select_source_title))
            .setMessage(getString(R.string.dialog_select_source_message))
            .setSingleChoiceItems(displayItems, checkedItemIndex) { dialog, which ->
                selectedSource = sources[which]
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showCancelConfirmationDialog() {
        if (isDeleting || !isOperationRunning.get()) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.dialog_cancel_download_title))
            .setMessage(getString(R.string.dialog_cancel_download_message))
            .setPositiveButton(getString(R.string.btn_yes)) { _, _ ->
                cancelActiveDownload()
            }
            .setNegativeButton(getString(R.string.btn_no), null)
            .show()
    }

    private fun cancelActiveDownload() {
        binding.statusText.text = getString(R.string.operation_cancelled)
        binding.downloadProgressBar.isIndeterminate = true
        binding.btnPauseResume.isEnabled = false
        binding.btnCancelDownload.isEnabled = false
        binding.btnPauseResume.alpha = 0.35f
        binding.btnCancelDownload.alpha = 0.35f
        
        downloadJob?.cancel()
        
        lifecycleScope.launch(Dispatchers.Main) {
            withContext(Dispatchers.IO) {
                requireContext().cacheDir?.listFiles()?.forEach { file ->
                    if (file.name.endsWith("_temp.zip")) {
                        file.delete()
                    }
                }
                
                activeGameId?.let { gameId ->
                    val outputDir = File(Environment.getExternalStorageDirectory(), "xash")
                    val targetGameDir = File(outputDir, gameId)
                    if (targetGameDir.exists()) {
                        targetGameDir.deleteRecursively()
                    }
                }
            }
            
            downloadJob = null
            activeGameId = null
            isDownloading = false
            isExtracting = false
            isDeleting = false
            isPaused.set(false)
            isOperationRunning.set(false)
            
            binding.progressContainer.visibility = View.GONE
            binding.downloadProgressBar.isIndeterminate = false
            adapter?.setInteractionEnabled(true)
            binding.btnPauseResume.isEnabled = true
            binding.btnCancelDownload.isEnabled = true
            binding.btnPauseResume.alpha = 1.0f
            binding.btnCancelDownload.alpha = 1.0f
        }
    }

    private fun checkHalfLifeRequirement(game: GameDownloadInfo, isHdSelected: Boolean) {
        val outputDir = File(Environment.getExternalStorageDirectory(), "xash")
        val hlDir = File(outputDir, "valve")

        if (game.id != "valve" && (!hlDir.exists() || hlDir.list()?.isNotEmpty() != true)) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.hl_required_title))
                .setMessage(getString(R.string.hl_required_message, game.name))
                .setPositiveButton(getString(R.string.btn_install_anyway)) { dialog, _ ->
                    dialog.dismiss()
                    checkFolderConflict(game, isHdSelected)
                }
                .setNegativeButton(getString(R.string.btn_cancel)) { dialog, _ ->
                    dialog.dismiss()
                }
                .setCancelable(true)
                .show()
        } else {
            checkFolderConflict(game, isHdSelected)
        }
    }

    private fun checkFolderConflict(game: GameDownloadInfo, isHdSelected: Boolean) {
        val outputDir = File(Environment.getExternalStorageDirectory(), "xash")
        val targetGameDir = File(outputDir, game.id)

        if (targetGameDir.exists() && targetGameDir.list()?.isNotEmpty() == true) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.conflict_title))
                .setMessage(getString(R.string.conflict_message))
                .setPositiveButton(getString(R.string.btn_replace)) { dialog, _ ->
                    dialog.dismiss()
                    startSequence(game, isHdSelected, ConflictStrategy.REPLACE)
                }
                .setNegativeButton(getString(R.string.btn_clean_install)) { dialog, _ ->
                    dialog.dismiss()
                    startSequence(game, isHdSelected, ConflictStrategy.CLEAN_INSTALL)
                }
                .setNeutralButton(getString(R.string.btn_skip_existing)) { dialog, _ ->
                    dialog.dismiss()
                    startSequence(game, isHdSelected, ConflictStrategy.SKIP_EXISTING)
                }
                .setCancelable(true)
                .show()
        } else {
            startSequence(game, isHdSelected, ConflictStrategy.REPLACE)
        }
    }

    private fun startSequence(game: GameDownloadInfo, isHdSelected: Boolean, strategy: ConflictStrategy) {
        if (!isOperationRunning.compareAndSet(false, true)) return 
        
        activeGameId = game.id

        adapter?.setInteractionEnabled(false)
        binding.progressContainer.visibility = View.VISIBLE
        binding.btnPauseResume.setIconResource(R.drawable.ic_baseline_pause_24px)
        binding.btnPauseResume.isEnabled = false
        binding.btnCancelDownload.isEnabled = false
        isPaused.set(false)

        downloadJob = lifecycleScope.launch(Dispatchers.Main) {
            val outputDir = File(Environment.getExternalStorageDirectory(), "xash")
            if (!outputDir.exists()) outputDir.mkdirs()

            if (strategy == ConflictStrategy.CLEAN_INSTALL) {
                isDeleting = true
                binding.statusText.text = getString(R.string.downloader_deleting_format, game.name)
                binding.downloadProgressBar.isIndeterminate = true
                
                binding.btnPauseResume.isEnabled = false
                binding.btnPauseResume.alpha = 0.35f
                binding.btnCancelDownload.isEnabled = false
                binding.btnCancelDownload.alpha = 0.35f
                
                val targetGameDir = File(outputDir, game.id)
                withContext(Dispatchers.IO) { targetGameDir.deleteRecursively() }
                isDeleting = false
            }

            var overallSuccess = true
            val baseLabel = getString(R.string.download_stage_base)
            
            isDownloading = true
            binding.btnPauseResume.isEnabled = true
            binding.btnPauseResume.alpha = 1.0f
            binding.btnCancelDownload.isEnabled = true
            binding.btnCancelDownload.alpha = 1.0f
            
            val baseSuccess = handleDownloadAndExtract(game.id, game.id, outputDir, strategy, baseLabel, game.baseSizeBytes)
            overallSuccess = baseSuccess

            if (downloadJob?.isCancelled == true || !overallSuccess) {
                finalizeSequence()
                return@launch
            }

            if (overallSuccess && isHdSelected) {
                val hdBranch = "${game.id}_hd"
                val hdLabel = getString(R.string.download_stage_hd)
                
                isDownloading = true
                binding.btnPauseResume.isEnabled = true
                binding.btnPauseResume.alpha = 1.0f
                binding.btnCancelDownload.isEnabled = true
                binding.btnCancelDownload.alpha = 1.0f
                
                val hdSuccess = handleDownloadAndExtract(hdBranch, game.id, outputDir, strategy, hdLabel, game.hdSizeBytes)
                overallSuccess = hdSuccess
            }

            if (overallSuccess) {
                Toast.makeText(context, getString(R.string.download_success, game.name), Toast.LENGTH_LONG).show()
            } else if (downloadJob?.isCancelled != true) {
                Toast.makeText(context, getString(R.string.download_failed), Toast.LENGTH_LONG).show()
            }

            finalizeSequence()
        }
    }

    private fun finalizeSequence() {
        cleanLegacyTempFiles()
        binding.progressContainer.visibility = View.GONE
        binding.downloadProgressBar.isIndeterminate = false
        adapter?.setInteractionEnabled(true)
        isDownloading = false
        isExtracting = false
        isDeleting = false
        downloadJob = null
        activeGameId = null
        isOperationRunning.set(false)
    }

    private suspend fun handleDownloadAndExtract(
        branchName: String,
        targetFolder: String,
        outputDir: File,
        strategy: ConflictStrategy,
        stageLabel: String,
        definedSize: Long
    ): Boolean {
        val zipUrl = selectedSource.urlPattern.replace("{branch}", branchName)
        val tempZipFile = File(requireContext().cacheDir, "${branchName}_temp.zip")

        binding.downloadProgressBar.isIndeterminate = false
        val downloadSuccess = withContext(Dispatchers.IO) {
            downloadFile(zipUrl, tempZipFile, definedSize) { progress, currentMb, totalMb ->
                lifecycleScope.launch(Dispatchers.Main) {
                    if (_binding != null && downloadJob?.isCancelled != true) {
                        binding.downloadProgressBar.progress = progress
                        binding.statusText.text = getString(R.string.downloader_status_format, stageLabel, currentMb, totalMb, progress)
                    }
                }
            }
        }

        if (!downloadSuccess || downloadJob?.isCancelled == true) {
            withContext(Dispatchers.IO) { tempZipFile.delete() }
            if (downloadJob?.isCancelled == true) {
                withContext(Dispatchers.IO) {
                    activeGameId?.let { File(outputDir, it).deleteRecursively() }
                }
            }
            return false
        }

        isDownloading = false
        isExtracting = true
        
        if (_binding != null) {
            binding.statusText.text = getString(R.string.downloader_extracting_format, stageLabel)
            binding.downloadProgressBar.isIndeterminate = true
            
            binding.btnPauseResume.isEnabled = false
            binding.btnPauseResume.alpha = 0.35f
            
            binding.btnCancelDownload.isEnabled = true
            binding.btnCancelDownload.alpha = 1.0f
        }

        val extractSuccess = withContext(Dispatchers.IO) {
            unzipAndStripRoot(tempZipFile, outputDir, branchName, targetFolder, strategy)
        }

        withContext(Dispatchers.IO) { tempZipFile.delete() }
        
        if (!extractSuccess || downloadJob?.isCancelled == true) {
            withContext(Dispatchers.IO) {
                activeGameId?.let { File(outputDir, it).deleteRecursively() }
            }
            isExtracting = false
            return false
        }

        isExtracting = false
        return true
    }

    private fun downloadFile(
        urlString: String, 
        outputFile: File, 
        definedSize: Long, 
        onProgress: (Int, String, String) -> Unit
    ): Boolean {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) return false

            val input = connection.inputStream
            val output = FileOutputStream(outputFile)

            val data = ByteArray(4096)
            var total: Long = 0
            var count: Int
            
            val totalMbText = if (definedSize > 0) String.format("%.2f", definedSize.toDouble() / (1024 * 1024)) else "?.??"

            while (input.read(data).also { count = it } != -1) {
                if (downloadJob?.isCancelled == true) {
                    output.close()
                    input.close()
                    return false
                }

                while (isPaused.get()) {
                    if (downloadJob?.isCancelled == true) {
                        output.close()
                        input.close()
                        return false
                    }
                    Thread.sleep(500)
                }

                total += count
                val currentMbText = String.format("%.2f", total.toDouble() / (1024 * 1024))
                
                var progressPercent = 0
                if (definedSize > 0) {
                    progressPercent = (total * 100 / definedSize).toInt().coerceAtMost(100)
                }
                
                onProgress(progressPercent, currentMbText, totalMbText)
                output.write(data, 0, count)
            }
            output.flush()
            output.close()
            input.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun unzipAndStripRoot(
        zipFile: File, 
        targetDir: File, 
        branchName: String, 
        targetFolder: String, 
        strategy: ConflictStrategy
    ): Boolean {
        return try {
            val prefix1 = "goldsrc-valve-$branchName/"
            val prefix2 = "$branchName/"
            
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (downloadJob?.isCancelled == true) return false

                    val entryName = entry.name
                    
                    val strippedPath = when {
                        entryName.startsWith(prefix1) -> entryName.substring(prefix1.length)
                        entryName.startsWith(prefix2) -> entryName.substring(prefix2.length)
                        else -> entryName
                    }
                    
                    if (strippedPath.isNotEmpty()) {
                        val targetGameDir = File(targetDir, targetFolder)
                        val destFile = File(targetGameDir, strippedPath)
                        
                        if (!destFile.canonicalPath.startsWith(targetGameDir.canonicalPath + File.separator)) {
                            return false
                        }
                        
                        if (entry.isDirectory) {
                            destFile.mkdirs()
                        } else {
                            if (strategy == ConflictStrategy.SKIP_EXISTING && destFile.exists()) {
                                zis.closeEntry()
                                entry = zis.nextEntry
                                continue
                            }
                            destFile.parentFile?.mkdirs()
                            FileOutputStream(destFile).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        downloadJob?.cancel()
        downloadJob = null
        _binding = null
    }
}
