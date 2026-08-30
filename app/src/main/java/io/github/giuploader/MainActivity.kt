package io.github.giuploader

import android.app.*
import android.content.*
import android.database.Cursor
import android.hardware.usb.*
import android.os.*
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.OpenableColumns
import android.view.*
import android.widget.*
import java.util.concurrent.Executors
import android.util.TypedValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

private const val STM32_VID = 0x0483

private const val ACTION_USB_PERMISSION = "io.github.giuploader.USB_PERMISSION"
private const val REQUEST_LOCAL_FIRMWARE = 1001

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var usb: UsbManager
    private lateinit var root: LinearLayout
    private var firmware: Firmware? = null
    private var chosen: FirmwareKind? = null
    private var chosenRelease: FirmwareRelease? = null
    private var firmwareDisplayName: String = ""
    private var result: Boolean = false
    private var permissionRequested = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            permissionRequested = false
            val grantedByCallback = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val grantedByManager = findDfuDevice()?.let { usb.hasPermission(it) } == true
            if (grantedByCallback || grantedByManager) {
                showReady()
            } else {
                showError("USB permission needed", "Allow GiUploader to access the STM32 bootloader, then try again.")
            }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        usb = getSystemService(USB_SERVICE) as UsbManager
        registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION), RECEIVER_NOT_EXPORTED)
        showSplash()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    private fun base(): LinearLayout {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(26), dp(28), dp(24))
            setBackgroundColor(Color.rgb(16, 24, 32))
        }
        return root
    }

    private fun showScrollable(view: LinearLayout) {
        val scroll = ScrollView(this).apply {
            setFillViewport(true)
            setBackgroundColor(Color.rgb(16, 24, 32))
        }
        scroll.addView(view, ViewGroup.LayoutParams(-1, -2))
        applySystemBarInsets(scroll)
        setContentView(scroll)
    }

    private fun applySystemBarInsets(view: View) {
        val initialLeft = view.paddingLeft
        val initialTop = view.paddingTop
        val initialRight = view.paddingRight
        val initialBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(
                initialLeft,
                initialTop + bars.top,
                initialRight,
                initialBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    private fun text(value: String, size: Float, color: Int = Color.WHITE): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            setPadding(0, dp(8), 0, dp(8))
        }
    }

    private fun button(label: String, enabled: Boolean = true): Button {
        return Button(this).apply {
            text = label
            isEnabled = enabled
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(172, 50, 59))
            minHeight = dp(54)
            minimumHeight = dp(54)
            includeFontPadding = true
        }
    }

    private fun screen(title: String, body: String): LinearLayout {
        val view = base()
        view.addView(text("GIUPLOADER", 13f, Color.rgb(172, 50, 59)))
        view.addView(text(title, 30f))
        view.addView(text(body, 17f, Color.LTGRAY))
        return view
    }

    private fun showSplash() {
        val view = base()
        view.gravity = Gravity.CENTER

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_chip)
            scaleType = ImageView.ScaleType.CENTER
        }
        view.addView(logo, LinearLayout.LayoutParams(-1, dp(120)))

        val title = text("GiUploader", 32f).apply {
            gravity = Gravity.CENTER
        }
        view.addView(title, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        applySystemBarInsets(view)
        setContentView(view)

        Handler(Looper.getMainLooper()).postDelayed({ showFirmwareChoice() }, 2000)
    }

    private fun showFirmwareChoice() {
        val view = screen("Loading releases", "Fetching available GiUCAN release tags from GitHub…")
        view.addView(ProgressBar(this))
        showScrollable(view)

        worker.execute {
            try {
                val availableReleases = FirmwareRepository.listReleases()
                runOnUiThread { showFirmwareSelection(availableReleases) }
            } catch (e: Exception) {
                runOnUiThread {
                    showError("Release lookup failed", e.message ?: "Could not fetch GiUCAN releases.")
                }
            }
        }
    }

    private fun showFirmwareSelection(availableReleases: List<FirmwareRelease>) {
        chosen = null
        chosenRelease = availableReleases.first()
        firmware = null
        firmwareDisplayName = ""
        val view = screen(
            "Choose firmware",
            "Select the GiUCAN firmware and release you want to install."
        )

        view.addView(text("Release tag", 15f, Color.LTGRAY))
        val releaseSpinner = Spinner(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(35, 48, 58))
                setStroke(dp(1), Color.rgb(172, 50, 59))
                cornerRadius = dp(4).toFloat()
            }
            setPadding(dp(12), 0, dp(12), 0)
        }
        releaseSpinner.adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            availableReleases.map { it.tagName }
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return super.getView(position, convertView, parent).apply {
                    (this as TextView).setTextColor(Color.WHITE)
                    textSize = 16f
                    setPadding(dp(12), 0, dp(12), 0)
                }
            }
        }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        releaseSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, item: View?, position: Int, id: Long) {
                chosenRelease = availableReleases[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val releaseSelector = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        releaseSelector.addView(
            releaseSpinner,
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )
        val infoButton = TextView(this).apply {
            text = "ⓘ"
            contentDescription = "Show release information"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
            minWidth = dp(26)
            minimumWidth = dp(26)
            minHeight = dp(26)
            minimumHeight = dp(26)
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(172, 50, 59))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { showReleaseInfo(chosenRelease ?: availableReleases.first()) }
        }
        releaseSelector.addView(
            infoButton,
            LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                setMargins(dp(8), 0, 0, 0)
            }
        )
        view.addView(releaseSelector)
        view.addView(text("Firmware variant", 15f, Color.LTGRAY).apply {
            setPadding(0, dp(18), 0, dp(8))
        })

        FirmwareKind.values().forEach { kind ->
            val firmwareButton = button(kind.label)
            firmwareButton.setOnClickListener {
                chosen = kind
                downloadFirmware()
            }
            view.addView(
                firmwareButton,
                LinearLayout.LayoutParams(-1, dp(58)).apply {
                    setMargins(0, dp(if (kind == FirmwareKind.SLCAN) 28 else 10), 0, 0)
                }
            )
        }

        view.addView(text("-- OR --", 15f, Color.LTGRAY).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(22), 0, dp(8))
        })
        view.addView(text("Load a firmware file from your device storage.", 15f, Color.LTGRAY).apply {
            gravity = Gravity.CENTER
        })
        val localButton = button("Pick firmware file")
        localButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                // ELF files are reported with different MIME types by storage providers.
                type = "*/*"
            }
            startActivityForResult(intent, REQUEST_LOCAL_FIRMWARE)
        }
        view.addView(localButton, buttonLayout(dp(10)))
        showScrollable(view)
    }

    @Deprecated("Use Activity Result APIs when this activity is migrated")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_LOCAL_FIRMWARE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        loadLocalFirmware(uri)
    }

    private fun loadLocalFirmware(uri: Uri) {
        val view = screen("Preparing firmware", "Loading the selected firmware file…")
        view.addView(ProgressBar(this))
        showScrollable(view)

        worker.execute {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Could not open the selected firmware file")
                require(bytes.isNotEmpty()) { "The selected firmware file is empty" }
                firmware = Firmware(bytes, localFirmwareName(uri))
                chosen = null
                chosenRelease = null
                firmwareDisplayName = firmware!!.name
                runOnUiThread { showConnect() }
            } catch (e: Exception) {
                runOnUiThread {
                    showError("Local firmware load failed", e.message ?: "Could not read the selected file.")
                }
            }
        }
    }

    private fun localFirmwareName(uri: Uri): String {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        return name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "Selected firmware"
    }

    private fun firmwareSourceLabel(): String = chosen?.label ?: "Local file"

    private fun firmwareSourceDetails(): String {
        return if (chosen != null && chosenRelease != null) {
            "Firmware: ${chosen!!.label}\nRelease: ${chosenRelease!!.tagName}"
        } else {
            "Firmware: ${firmwareDisplayName.ifBlank { firmware?.name ?: "Selected local file" }}"
        }
    }

    private fun showReleaseInfo(release: FirmwareRelease) {
        val published = release.publishedAt.ifBlank { "Unknown" }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Release information")
            .setMessage("Loading release notes…")
            .setPositiveButton("Close", null)
            .setCancelable(true)
            .show()

        worker.execute {
            try {
                val notes = FirmwareRepository.fetchReleaseBody(release)
                runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    val details = buildString {
                        append("Name: ${release.name}\nPublished: $published")
                        if (notes.isNotBlank()) {
                            append("\n\nNotes:\n")
                            append(notes)
                        }
                    }
                    dialog.setMessage(details)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    dialog.setMessage(
                        "Name: ${release.name}\nPublished: $published\n\n" +
                            "Could not load release notes."
                    )
                }
            }
        }
    }

    private fun downloadFirmware() {
        val kind = chosen ?: return
        val release = chosenRelease ?: return
        val view = screen(
            "Preparing firmware",
            "Downloading ${kind.label} firmware from release ${release.tagName}…"
        )
        view.addView(ProgressBar(this))
        showScrollable(view)

        worker.execute {
            try {
                firmware = FirmwareRepository.download(this, kind, release)
                firmwareDisplayName = firmware!!.name
                runOnUiThread { showConnect() }
            } catch (e: Exception) {
                runOnUiThread {
                    showError("Download failed", e.message ?: "Could not download firmware.")
                }
            }
        }
    }

    private fun showConnect() {
        permissionRequested = false
        val view = screen(
            "Connect your board",
            "Put the STM32 board in DFU mode, then connect it with a USB cable.\n\n" +
                "We’ll check automatically.\n\n" +
                firmwareSourceDetails()
        )
        val status = text("Waiting for STM32 bootloader…", 16f, Color.rgb(172, 50, 59))
        view.addView(status)
        showScrollable(view)

        val handler = Handler(Looper.getMainLooper())
        val check = object : Runnable {
            override fun run() {
                val device = findDfuDevice()
                if (device != null) {
                    handler.removeCallbacks(this)
                    status.text = "STM32 bootloader detected"
                    if (!permissionRequested) {
                        permissionRequested = true
                        requestUsb(device)
                    }
                } else {
                    permissionRequested = false
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.post(check)
    }

    private fun findDfuDevice(): UsbDevice? {
        return usb.deviceList.values.firstOrNull {
            it.vendorId == STM32_VID && (it.productId == 0xdf11 || it.deviceClass == 0)
        }
    }

    private fun requestUsb(device: UsbDevice) {
        if (usb.hasPermission(device)) {
            permissionRequested = false
            showReady()
            return
        }

        val intent = Intent(ACTION_USB_PERMISSION)
            .setPackage(packageName)
            .putExtra(UsbManager.EXTRA_DEVICE, device)
        val pending = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        usb.requestPermission(device, pending)
    }

    private fun showReady() {
        val view = screen(
            "Ready to upload",
            "${firmwareSourceLabel()} firmware is ready. Choose how to program your board."
        )
        val upload = button("Upload")
        val erase = button("Erase and Upload")
        upload.setOnClickListener { startUpload(false) }
        erase.setOnClickListener { startUpload(true) }
        view.addView(upload, buttonLayout(dp(22), dp(8)))
        view.addView(erase, buttonLayout())
        showScrollable(view)
    }

    private fun startUpload(erase: Boolean) {
        val view = screen(
            "Uploading ${firmwareSourceLabel()}",
            "${firmwareSourceDetails()}\nErasing flash and writing firmware…"
        )
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        view.addView(progressBar, LinearLayout.LayoutParams(-1, dp(28)).apply {
            setMargins(0, dp(24), 0, 0)
        })
        val status = text("Starting…", 15f, Color.LTGRAY)
        view.addView(status)
        showScrollable(view)

        worker.execute {
            try {
                val device = findDfuDevice() ?: error("Board disconnected")
                val connection = usb.openDevice(device) ?: error("USB permission was not granted")
                DfuUploader(connection, device, BoardProfiles.STM32F072_128K)
                    .upload(firmware!!.bytes, erase) { value, message ->
                        runOnUiThread {
                            progressBar.progress = value
                            status.text = message
                        }
                    }
                connection.close()
                result = true
                runOnUiThread { showResult(null) }
            } catch (e: Exception) {
                result = false
                runOnUiThread { showResult(e.message ?: "Upload failed") }
            }
        }
    }

    private fun showResult(error: String?) {
        val success = error == null || error.startsWith("Upload completed")
        val resultMessage = if (success) {
            "Your ${firmwareSourceLabel()} firmware was written successfully."
        } else {
            "${firmwareSourceDetails()}\n\n$error"
        }
        val view = screen(
            if (success) "Upload complete" else "Upload failed",
            resultMessage
        )
        val icon = ImageView(this).apply {
            setImageResource(if (success) R.drawable.ic_upload_success else R.drawable.ic_upload_error)
            contentDescription = if (success) "Upload successful" else "Upload failed"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        view.addView(icon, LinearLayout.LayoutParams(-1, dp(96)).apply {
            setMargins(0, dp(12), 0, 0)
        })

        val again = button("Upload another firmware")
        again.setOnClickListener { showFirmwareChoice() }
        val close = button("Close")
        close.setOnClickListener { finish() }
        view.addView(again, buttonLayout(dp(25), dp(8)))
        view.addView(close, buttonLayout())
        showScrollable(view)
    }

    private fun showError(title: String, body: String) {
        val view = screen(title, body)
        val retry = button("Try again")
        retry.setOnClickListener { showFirmwareChoice() }
        view.addView(retry, buttonLayout(dp(25)))
        showScrollable(view)
    }

    private fun buttonLayout(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(-1, dp(58)).apply {
            setMargins(0, top, 0, bottom)
        }
    }
}
