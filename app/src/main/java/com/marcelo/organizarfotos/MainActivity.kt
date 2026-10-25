package com.marcelo.organizarfotos

import android.app.Activity
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var log: TextView
    private val fotos = setOf("jpg", "jpeg", "png", "heic", "webp", "gif")
    private val videos = setOf("mp4", "3gp", "mkv", "mov", "webm")
    private val raiz = Environment.getExternalStorageDirectory()
    private val dcim = File(raiz, "DCIM")
    private val pastaData = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val fmtPasta = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tela = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 40) }
        val botao = Button(this).apply { text = "Organizar fotos e vídeos" }
        log = TextView(this)
        val rolagem = ScrollView(this).apply { addView(log) }
        tela.addView(botao)
        tela.addView(rolagem)
        setContentView(tela)
        botao.setOnClickListener {
            if (!Environment.isExternalStorageManager()) {
                escrever("Libere 'Acesso a todos os arquivos' e toque de novo.")
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                botao.isEnabled = false
                Thread { organizar(); runOnUiThread { botao.isEnabled = true } }.start()
            }
        }
    }

    private fun escrever(t: String) = runOnUiThread { log.append(t + "\n") }

    private fun organizar() {
        escrever("Procurando arquivos...")
        val lista = mutableListOf<File>()
        coletar(raiz, lista)
        escrever("Encontrados: ${lista.size}")
        val caminhos = mutableListOf<String>()
        var ok = 0
        val comData = lista.map { it to dataDe(it) }.sortedBy { it.second }
        val contador = mutableMapOf<String, Int>()
        // 1a etapa: nomes temporarios para nao dar conflito ao renomear
        val temp = comData.mapIndexed { k, (f, d) ->
            val t = File(f.parentFile, "tmp_org_${k}.${f.extension}")
            caminhos.add(f.absolutePath)
            (if (f.renameTo(t)) t else f) to d
        }
        for ((f, data) in temp) {
            try {
                val dia = fmtPasta.format(data)
                val destDir = File(dcim, dia)
                destDir.mkdirs()
                var dest: File
                var i = contador[dia] ?: 0
                do { dest = File(destDir, "${dia}_${letras(i)}.${f.extension.lowercase()}"); i++ } while (dest.exists())
                contador[dia] = i
                if (!f.renameTo(dest)) { f.copyTo(dest); f.delete() }
                caminhos.add(f.absolutePath); caminhos.add(dest.absolutePath)
                ok++
            } catch (e: Exception) {
                escrever("Erro em ${f.name}: ${e.message}")
            }
        }
        MediaScannerConnection.scanFile(this, caminhos.toTypedArray(), null, null)
        escrever("Concluído: $ok arquivos movidos.")
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
                val ignorar = f.name.startsWith(".") ||
                    f.absolutePath == File(raiz, "Android/data").absolutePath ||
                    f.absolutePath == File(raiz, "Android/obb").absolutePath ||
                    f.name.contains("Stickers", true) ||
                    false
                if (!ignorar) coletar(f, lista)
            } else {
                val ext = f.extension.lowercase()
                if (!f.name.startsWith(".") && (ext in fotos || ext in videos)) lista.add(f)
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
        // Data no nome do arquivo (ex.: IMG-20260915-WA0001.jpg)
        Regex("(20\\d{2})(\\d{2})(\\d{2})").find(f.name)?.let {
            try { return SimpleDateFormat("yyyyMMdd", Locale.US).parse(it.value)!! } catch (_: Exception) { }
        }
        return Date(f.lastModified())
    }
}
