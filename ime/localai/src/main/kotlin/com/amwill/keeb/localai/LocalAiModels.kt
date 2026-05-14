package com.amwill.keeb.localai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.amwill.keeb.models.AndroidDeviceCapabilityDetector
import com.amwill.keeb.models.LocalModelFileScanner
import com.amwill.keeb.models.ModelCatalog
import com.amwill.keeb.models.ModelDownloadProgress
import com.amwill.keeb.models.ModelFileCheck
import com.amwill.keeb.models.ModelInstallResult
import com.amwill.keeb.models.ModelInventory
import com.amwill.keeb.models.ModelStatus
import com.amwill.keeb.models.SharedPreferencesModelInventoryStore
import com.amwill.keeb.models.StreamingModelInstaller
import com.amwill.keeb.models.UrlModelStreamDownloader
import com.amwill.keeb.models.WhisperModel
import com.amwill.keeb.models.installedModelFile
import com.amwill.keeb.models.verifyInstalledModelFile
import java.io.File

class LocalAiSettingsFragment : Fragment() {
    private lateinit var repository: LocalAiModelRepository
    private lateinit var root: LinearLayout
    private var progressBar: ProgressBar? = null
    private var operationStatus: TextView? = null
    private var activeDownloadModelId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = LocalAiModelRepository(requireContext().applicationContext)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padding = resources.displayMetrics.density.times(16).toInt()
            setPadding(padding, padding, padding, padding)
        }
        return ScrollView(requireContext()).apply { addView(root) }
    }

    override fun onStart() {
        super.onStart()
        requireActivity().title = "LocalAI Voice"
        render()
    }

    @Deprecated("Deprecated in AndroidX Fragment, but sufficient for this focused settings screen.")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) render()
    }

    private fun render() {
        root.removeAllViews()
        root.addText("LocalAI Voice", sizeSp = 24f)
        root.addText(
            "Voice typing runs on this device with whisper.cpp. Spoken audio is recorded only for the current push-to-talk session and is not sent to cloud speech services.",
        )

        val hasMic = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        root.addText("Microphone permission: ${if (hasMic) "granted" else "required"}")
        if (!hasMic) {
            root.addButton("Grant microphone permission") {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            }
        }

        val capability = repository.deviceCapability()
        root.addText("Device: ${capability.profile.availableRamMb} MB RAM, ${capability.cpuCores} CPU cores, ABI ${capability.primaryAbi}")
        root.addText("Model directory: ${repository.modelDirectory.absolutePath}")

        progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).also {
            it.max = 1000
            it.progress = 0
            it.visibility = if (activeDownloadModelId == null) View.GONE else View.VISIBLE
            root.addView(it)
        }
        operationStatus = TextView(requireContext()).apply {
            textSize = 14f
            setPadding(0, 8, 0, 8)
            visibility = View.GONE
            root.addView(this)
        }

        root.addText("Models", sizeSp = 20f)
        repository.records().forEach { record -> root.addModelRow(record) }
    }

    private fun LinearLayout.addModelRow(record: ModelUiRecord) {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 12)
            isClickable = true
            setOnClickListener { handlePrimaryModelAction(record) }

            addView(TextView(context).apply {
                textSize = 18f
                text = "${record.displayName}${if (record.selected) " (selected)" else ""}"
            })
            addView(TextView(context).apply {
                textSize = 14f
                setPadding(0, 4, 0, 4)
                text = modelStatusText(record)
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(context).apply {
                    isAllCaps = false
                    text = primaryActionLabel(record)
                    isEnabled = activeDownloadModelId == null
                    setOnClickListener { handlePrimaryModelAction(record) }
                })
                if (record.status == ModelUiStatus.INSTALLED) {
                    addView(Button(context).apply {
                        isAllCaps = false
                        text = if (record.selected) "Delete selected model" else "Delete"
                        isEnabled = activeDownloadModelId == null
                        setOnClickListener {
                            toast(repository.delete(record.modelId))
                            render()
                        }
                    })
                }
            })
        })
    }

    private fun handlePrimaryModelAction(record: ModelUiRecord) {
        when (record.status) {
            ModelUiStatus.INSTALLED -> {
                toast(if (record.selected) "Already selected: ${record.displayName}" else repository.select(record.modelId))
                render()
            }
            ModelUiStatus.NOT_DOWNLOADED,
            ModelUiStatus.FAILED,
            -> download(record)
            ModelUiStatus.BLOCKED -> toast(record.reason ?: "This model is not available on this device.")
        }
    }

    private fun download(record: ModelUiRecord) {
        if (activeDownloadModelId != null) {
            toast("A model install is already running.")
            return
        }
        activeDownloadModelId = record.modelId
        render()
        progressBar?.visibility = View.VISIBLE
        progressBar?.progress = 0
        updateOperationStatus("Starting install for ${record.displayName}...")
        Thread {
            val message = repository.download(record.modelId) { progress ->
                view?.post {
                    progressBar?.progress = (progress.fraction * 1000).toInt()
                    val downloadedMiB = progress.bytesDownloaded / (1024L * 1024L)
                    val totalMiB = progress.totalBytes / (1024L * 1024L)
                    val state = if (progress.fraction >= 1f) "Verifying" else "Downloading"
                    updateOperationStatus("$state ${record.displayName}: $downloadedMiB / $totalMiB MiB")
                }
            }
            view?.post {
                activeDownloadModelId = null
                progressBar?.visibility = View.GONE
                updateOperationStatus(message)
                toast(message)
                render()
            }
        }.apply {
            name = "KeebLocalAiModelDownload"
            start()
        }
    }

    private fun primaryActionLabel(record: ModelUiRecord): String = when (record.status) {
        ModelUiStatus.NOT_DOWNLOADED -> "Install"
        ModelUiStatus.INSTALLED -> if (record.selected) "Selected" else "Select"
        ModelUiStatus.BLOCKED -> "Not supported"
        ModelUiStatus.FAILED -> "Retry install"
    }

    private fun modelStatusText(record: ModelUiRecord): String {
        val status = when (record.status) {
            ModelUiStatus.NOT_DOWNLOADED -> "Not installed"
            ModelUiStatus.INSTALLED -> if (record.selected) "Installed and selected" else "Installed"
            ModelUiStatus.BLOCKED -> "Blocked: ${record.reason}"
            ModelUiStatus.FAILED -> "Failed: ${record.reason}"
        }
        return buildString {
            append("${record.sizeMiB} MiB, min ${record.minRamMb} MB RAM, $status")
            if (!record.localPath.isNullOrBlank()) append("\n${record.localPath}")
        }
    }

    private fun updateOperationStatus(message: CharSequence) {
        operationStatus?.apply {
            text = message
            visibility = View.VISIBLE
        }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun LinearLayout.addText(text: CharSequence, sizeSp: Float = 14f) {
        addView(TextView(context).apply {
            setText(text)
            textSize = sizeSp
            setPadding(0, 10, 0, 10)
        })
    }

    private fun LinearLayout.addButton(text: CharSequence, onClick: () -> Unit) {
        addView(Button(context).apply {
            isAllCaps = false
            setText(text)
            setSingleLine(false)
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { onClick() }
        })
    }

    private companion object {
        const val REQUEST_RECORD_AUDIO = 7331
    }
}

enum class ModelUiStatus { NOT_DOWNLOADED, INSTALLED, BLOCKED, FAILED }

data class ModelUiRecord(
    val modelId: String,
    val displayName: String,
    val sizeMiB: Long,
    val minRamMb: Int,
    val selected: Boolean,
    val localPath: String?,
    val status: ModelUiStatus,
    val reason: String?,
)

class LocalAiModelRepository(private val context: android.content.Context) {
    val modelDirectory: File = File(context.filesDir, MODEL_DIRECTORY)
    private val store = SharedPreferencesModelInventoryStore(context)

    fun deviceCapability() = AndroidDeviceCapabilityDetector(context).detect()

    fun records(): List<ModelUiRecord> {
        val inventory = inventory()
        val profile = deviceCapability().profile
        return inventory.records().map { record ->
            val gate = inventory.downloadGate(record.model.id, profile)
            val status = when (val current = record.status) {
                ModelStatus.Installed -> ModelUiStatus.INSTALLED
                is ModelStatus.Failed -> ModelUiStatus.FAILED
                is ModelStatus.Blocked -> ModelUiStatus.BLOCKED
                ModelStatus.NotDownloaded -> if (gate is ModelStatus.Blocked) ModelUiStatus.BLOCKED else ModelUiStatus.NOT_DOWNLOADED
            }
            val reason = when (val current = record.status) {
                is ModelStatus.Failed -> current.reason
                is ModelStatus.Blocked -> current.reason
                else -> (gate as? ModelStatus.Blocked)?.reason
            }
            ModelUiRecord(
                modelId = record.model.id,
                displayName = record.model.displayName,
                sizeMiB = record.model.bytes / (1024L * 1024L),
                minRamMb = record.model.minRamMb,
                selected = record.selected,
                localPath = record.model.localPath,
                status = status,
                reason = reason,
            )
        }
    }

    fun download(modelId: String, progress: (ModelDownloadProgress) -> Unit): String {
        val model = ModelCatalog.pinned.firstOrNull { it.id == modelId } ?: return "Unknown model: $modelId"
        when (val gate = inventory().downloadGate(modelId, deviceCapability().profile)) {
            is ModelStatus.Blocked -> return gate.reason
            else -> Unit
        }
        val installer = StreamingModelInstaller(modelDirectory, UrlModelStreamDownloader())
        val existingFile = installedModelFile(modelDirectory, model)
        val alreadyInstalled = verifyInstalledModelFile(model, existingFile) is ModelFileCheck.Valid
        return when (val result = installer.install(model, progress)) {
            is ModelInstallResult.Installed -> {
                val next = inventory()
                next.markInstalled(modelId, result.file.absolutePath)
                next.select(modelId)
                store.save(next.snapshot())
                if (alreadyInstalled) {
                    "Using installed file and selected: ${result.file.absolutePath}"
                } else {
                    "Downloaded, verified, and selected: ${result.file.absolutePath}"
                }
            }
            is ModelInstallResult.Failed -> {
                val next = inventory()
                next.markFailed(modelId, result.reason)
                store.save(next.snapshot())
                result.reason
            }
        }
    }

    fun select(modelId: String): String {
        val model = ModelCatalog.pinned.firstOrNull { it.id == modelId } ?: return "Unknown model: $modelId"
        val file = installedModelFile(modelDirectory, model)
        return when (val check = verifyInstalledModelFile(model, file)) {
            is ModelFileCheck.Valid -> {
                val next = inventory()
                next.markInstalled(modelId, check.file.absolutePath)
                next.select(modelId)
                store.save(next.snapshot())
                "Selected ${model.displayName}"
            }
            ModelFileCheck.Missing -> "Model file is missing. Download it first."
            is ModelFileCheck.Invalid -> check.reason
        }
    }

    fun delete(modelId: String): String {
        val model = ModelCatalog.pinned.firstOrNull { it.id == modelId } ?: return "Unknown model: $modelId"
        val fileDeleted = installedModelFile(modelDirectory, model).delete()
        val next = inventory()
        next.delete(modelId)
        store.save(next.snapshot())
        return if (fileDeleted) "Deleted ${model.displayName}" else "Removed ${model.displayName} from LocalAI inventory"
    }

    private fun inventory(): ModelInventory {
        val installed = LocalModelFileScanner(modelDirectory).installedRecords()
        val inventory = ModelInventory(persistedRecords = installed + store.load())
        store.save(inventory.snapshot())
        return inventory
    }

    private companion object {
        const val MODEL_DIRECTORY = "whisper-models"
    }
}
