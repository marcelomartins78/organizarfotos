package com.marcelo.organizarfotos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ServicoOrganizar : Service() {

    companion object {
        @Volatile var rodando = false
        @Volatile var status = "Toque no botão para começar."
        val log = StringBuffer()
    }

    private val fotos = setOf("jpg", "jpeg", "png", "heic", "webp", "gif")
    private val videos = setOf("mp4", "3gp", "mkv", "mov", "webm")
    private val raiz = Environment.getExternalStorageDirectory()
    private val dcim = File(raiz, "DCIM")
    private val pastaData = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val fmtPasta = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (rodando) return START_NOT_STICKY
        rodando = true
        log.setLength(0)
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("org", "Organizar fotos", NotificationManager.IMPORTANCE_LOW))
        val n = notificacao("Iniciando...", 0, 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
        val wl = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "organizarfotos:copia")
        wl.acquire()
        Thread {
            try { organizar() } catch (e: Exception) { escrever("Falha geral: ${e.message}") }
            finally {
                wl.release()
                rodando = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                nm.notify(2, Notification.Builder(this, "org")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Organizar fotos — concluído")
                    .setContentText(status.replace("\n", " | "))
                    .build())
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun notificacao(texto: String, max: Int, atual: Int): Notification =
        Notification.Builder(this, "org")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Organizar fotos")
            .setContentText(texto)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(max, atual, max == 0)
            .build()

    private fun escrever(t: String) { log.append(t + "\n") }

    private fun organizar() {
        status = "Procurando arquivos..."
        val lista = mutableListOf<File>()
        coletar(raiz, lista)
        val total = lista.size
        escrever("Encontrados: $total")

        val comData = lista.mapIndexed { k, f ->
            if (k % 50 == 0) {
                status = "Encontrados: $total\nLendo datas: $k de $total"
                nm.notify(1, notificacao("Lendo datas: $k de $total", 0, 0))
            }
            f to dataDe(f)
        }.sortedBy { it.second }

        val caminhos = mutableListOf<String>()
        val contador = mutableMapOf<String, Int>()
        var ok = 0
        var erros = 0
        for ((idx, par) in comData.withIndex()) {
            val (f, data) = par
            try {
                val dia = fmtPasta.format(data)
                val destDir = File(dcim, dia)
                destDir.mkdirs()
                var dest: File
                var i = contador[dia] ?: 0
                do { dest = File(destDir, "${dia}_${letras(i)}.${f.extension.lowercase()}"); i++ } while (dest.exists())
                contador[dia] = i
                f.copyTo(dest, overwrite = false)
                if (dest.length() == f.length()) {
                    dest.setLastModified(data.time)
                    caminhos.add(dest.absolutePath)
                    ok++
                } else {
                    dest.delete(); erros++
                    escrever("Cópia incompleta: ${f.absolutePath}")
                }
            } catch (e: Exception) {
                erros++
                escrever("Erro em ${f.absolutePath}: ${e.message}")
            }
            val feitos = idx + 1
            status = "Encontrados: $total\nCopiados: $ok\nErros: $erros\nFaltam: ${total - feitos}"
            if (feitos % 20 == 0 || feitos == total)
                nm.notify(1, notificacao("$feitos de $total — erros: $erros", total, feitos))
        }
        MediaScannerConnection.scanFile(this, caminhos.toTypedArray(), null, null)
        status += "\nCONCLUÍDO. Os originais NÃO foram apagados."
    }

    private fun letras(n: Int): String {
        var x = n
        var s = ""
        do { s = ('A' + x % 26).toString() + s; x = x / 26 - 1 } while (x >= 0)
        return s
    }

    private fun coletar(dir: File, lista: MutableList<File>) {
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                val ignorar = f.name.contains("thumb", true) ||
                    f.absolutePath == File(raiz, "Android/data").absolutePath ||
                    f.absolutePath == File(raiz, "Android/obb").absolutePath ||
                    f.name.contains("Stickers", true) ||
                    (f.parentFile == dcim && pastaData.matches(f.name))
                if (!ignorar) coletar(f, lista)
            } else {
                val ext = f.extension.lowercase()
                if (ext in fotos || ext in videos) lista.add(f)
            }
        }
    }

    private fun dataDe(f: File): Date {
        val ext = f.extension.lowercase()
        try {
            if (ext in fotos) {
                val exif = ExifInterface(f)
                val s = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                if (s != null) return SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(s)!!
            } else {
                val r = MediaMetadataRetriever()
                r.setDataSource(f.absolutePath)
                val s = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                r.release()
                if (s != null && s.length >= 15) {
                    val d = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).parse(s.substring(0, 15))!!
                    if (d.year + 1900 > 1980) return d
                }
            }
        } catch (_: Exception) { }
        Regex("(20\\d{2})(\\d{2})(\\d{2})").find(f.name)?.let {
            try { return SimpleDateFormat("yyyyMMdd", Locale.US).parse(it.value)!! } catch (_: Exception) { }
        }
        return Date(f.lastModified())
    }
}
