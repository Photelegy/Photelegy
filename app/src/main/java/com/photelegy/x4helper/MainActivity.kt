package com.photelegy.x4helper

import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var formatSpinner: Spinner
    private lateinit var hostInput: EditText
    private lateinit var statusText: TextView

    private var lastExport: File? = null
    private var pendingMode: ProcessingMode = ProcessingMode.PHOTO

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handlePickedImage(it, pendingMode) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        formatSpinner = findViewById(R.id.formatSpinner)
        hostInput = findViewById(R.id.hostInput)
        statusText = findViewById(R.id.statusText)

        val formatAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            OutputFormat.values().map { it.name }
        )
        formatSpinner.adapter = formatAdapter

        findViewById<Button>(R.id.convertPhotoButton).setOnClickListener {
            pendingMode = ProcessingMode.PHOTO
            pickImageLauncher.launch("image/*")
        }

        findViewById<Button>(R.id.convertScreenshotButton).setOnClickListener {
            pendingMode = ProcessingMode.SCREENSHOT
            pickImageLauncher.launch("image/*")
        }

        findViewById<Button>(R.id.sendButton).setOnClickListener {
            sendLastExport()
        }
    }

    private fun handlePickedImage(uri: Uri, mode: ProcessingMode) {
        try {
            statusText.text = "Verarbeite Bild..."
            val bitmap = ImageProcessor.loadBitmap(contentResolver, uri)
            val processed = when (mode) {
                ProcessingMode.PHOTO -> ImageProcessor.processForPhoto(bitmap)
                ProcessingMode.SCREENSHOT -> ImageProcessor.processForScreenshot(bitmap)
            }
            val format = selectedFormat()
            val output = ImageProcessor.saveBitmap(this, processed.bitmap, processed.suggestedName, format)
            lastExport = output
            statusText.text = "Gespeichert: ${output.name}"
            Toast.makeText(this, "Datei gespeichert in ${output.parent}", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            statusText.text = "Fehler: ${error.message}"
        }
    }

    private fun selectedFormat(): OutputFormat {
        val name = formatSpinner.selectedItem?.toString()?.uppercase(Locale.getDefault()) ?: OutputFormat.JPG.name
        return OutputFormat.valueOf(name)
    }

    private fun sendLastExport() {
        val host = hostInput.text.toString().trim()
        val file = lastExport
        if (host.isBlank()) {
            Toast.makeText(this, "Bitte X4 Adresse eingeben.", Toast.LENGTH_SHORT).show()
            return
        }
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Bitte zuerst eine Datei erzeugen.", Toast.LENGTH_SHORT).show()
            return
        }
        statusText.text = "Sende Datei an X4..."
        Thread {
            val result = sendFileToX4(host, file)
            runOnUiThread {
                statusText.text = result
            }
        }.start()
    }

    private fun sendFileToX4(host: String, file: File): String {
        return try {
            val url = URL("http://$host:8080/upload")
            val boundary = "----X4Boundary${System.currentTimeMillis()}"
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            connection.outputStream.use { output ->
                output.write("--$boundary\r\n".toByteArray())
                output.write(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n".toByteArray()
                )
                output.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
                output.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                "Upload erfolgreich (HTTP $responseCode)."
            } else {
                "Upload fehlgeschlagen (HTTP $responseCode)."
            }
        } catch (error: Exception) {
            "Fehler beim Senden: ${error.message}"
        }
    }
}

enum class ProcessingMode {
    PHOTO,
    SCREENSHOT
}
