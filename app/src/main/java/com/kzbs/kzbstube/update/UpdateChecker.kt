package com.kzbs.kzbstube.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.kzbs.kzbstube.BuildConfig
import com.kzbs.kzbstube.R
import com.kzbs.kzbstube.model.UpdateModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class UpdateChecker(private val context: Context) {

    companion object {
        // Direct GitHub API raw content — zero CDN cache delay
        const val API_UPDATE_URL = "https://api.github.com/repos/kzbs4652/KZBStube/contents/version.json"
        const val RAW_UPDATE_URL = "https://raw.githubusercontent.com/kzbs4652/KZBStube/main/version.json"
    }

    fun checkForUpdates(isManual: Boolean = false) {
        CoroutineScope(Dispatchers.IO).launch {
            var jsonContent: String? = null

            // 1. Try Direct GitHub API with zero-cache raw header
            try {
                val conn = URL("$API_UPDATE_URL?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3.raw")
                conn.setRequestProperty("User-Agent", "KZBStube")
                conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                conn.connect()

                if (conn.responseCode == 200) {
                    jsonContent = conn.inputStream.bufferedReader().use { it.readText() }
                }
            } catch (_: Exception) {}

            // 2. Fallback to raw githubusercontent with cache buster timestamp
            if (jsonContent == null) {
                try {
                    val conn = URL("$RAW_UPDATE_URL?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("User-Agent", "KZBStube")
                    conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                    conn.connect()

                    if (conn.responseCode == 200) {
                        jsonContent = conn.inputStream.bufferedReader().use { it.readText() }
                    }
                } catch (_: Exception) {}
            }

            withContext(Dispatchers.Main) {
                if (jsonContent != null) {
                    try {
                        val update = Gson().fromJson(jsonContent, UpdateModel::class.java)
                        if (update.latestVersionCode > BuildConfig.VERSION_CODE) {
                            showUpdateDialog(update)
                        } else if (isManual) {
                            Toast.makeText(context, "En güncel sürümü kullanıyorsunuz (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        if (isManual) {
                            Toast.makeText(context, "Güncelleme bilgisi okunamadı.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else if (isManual) {
                    Toast.makeText(context, "Güncelleme sunucusuna bağlanılamadı. İnternet bağlantınızı kontrol edin.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showUpdateDialog(update: UpdateModel) {
        if (context !is Activity || context.isFinishing) return

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_update, null)
        val tvVersion = view.findViewById<TextView>(R.id.tvUpdateVersion)
        val tvChangelog = view.findViewById<TextView>(R.id.tvUpdateChangelog)
        val pbDownload = view.findViewById<ProgressBar>(R.id.pbUpdateDownload)
        val btnLater = view.findViewById<Button>(R.id.btnUpdateLater)
        val btnNow = view.findViewById<Button>(R.id.btnUpdateNow)

        tvVersion.text = "v${BuildConfig.VERSION_NAME} ➔ v${update.latestVersionName}"
        tvChangelog.text = update.changelog

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setCancelable(!update.isMandatory)
            .create()

        btnLater.setOnClickListener { dialog.dismiss() }
        btnNow.setOnClickListener {
            btnNow.isEnabled = false
            btnLater.isEnabled = false
            pbDownload.visibility = View.VISIBLE
            downloadAndInstall(update.apkUrl, pbDownload, dialog)
        }

        dialog.show()
    }

    private fun downloadAndInstall(apkUrl: String, pb: ProgressBar, dialog: AlertDialog) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(apkUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connect()

                val fileLength = conn.contentLength
                val apkFile = File(context.cacheDir, "KZBStube_update.apk")
                if (apkFile.exists()) apkFile.delete()

                val input = conn.inputStream
                val output = FileOutputStream(apkFile)
                val data = ByteArray(4096)
                var total: Long = 0
                var count: Int

                while (input.read(data).also { count = it } != -1) {
                    total += count.toLong()
                    if (fileLength > 0) {
                        val progress = ((total * 100) / fileLength).toInt()
                        withContext(Dispatchers.Main) { pb.progress = progress }
                    }
                    output.write(data, 0, count)
                }

                output.flush()
                output.close()
                input.close()

                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    installApk(apkFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "İndirme hatası: ${e.message}", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                }
            }
        }
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
