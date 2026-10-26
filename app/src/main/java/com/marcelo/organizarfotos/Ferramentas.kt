package com.marcelo.organizarfotos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class Midia(val arquivo: File, val data: Date) {
    val video = arquivo.extension.lowercase() in Ferramentas.videos
    val migrada = Ferramentas.estaMigrada(arquivo)
}

class Cabecalho(val dia: String, val qtd: Int, val tudoVerde: Boolean)
class Linha(val itens: List<Midia>)

object Ferramentas {
    val fotos = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif")
    val videos = setOf("mp4", "3gp", "mkv", "mov", "webm")
    val raiz: File = Environment.getExternalStorageDirectory()
    val dcim = File(raiz, "DCIM")
    val lixeira = File(raiz, ".LixeiraOrganizarFotos")
    private val indice = File(lixeira, "indice.txt")
    private val nomeCerto = Regex("([0-9]{4}-[0-9]{2}-[0-9]{2})_[A-Z]+[.][A-Za-z0-9]+")

    fun dia(d: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)
    fun dataHora(d: Date): String = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(d)

    fun ehMidia(f: File): Boolean {
        val ext = f.extension.lowercase()
        return ext in fotos || ext in videos
    }

    // Verde: está em DCIM/AAAA-MM-DD e o nome começa com a mesma data + letras
    fun estaMigrada(f: File): Boolean {
        val m = nomeCerto.matchEntire(f.name) ?: return false
        val pasta = f.parentFile ?: return false
        return pasta.name == m.groupValues[1] && pasta.parentFile?.absolutePath == dcim.absolutePath
    }

    fun letras(n: Int): String {
        var x = n
        var s = ""
        do { s = ('A' + x % 26).toString() + s; x = x / 26 - 1 } while (x >= 0)
        return s
    }

    fun coletar(dir: File, lista: MutableList<File>) {
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                val c = f.absolutePath
                val ignorar = f.name.contains("thumb", true) ||
                    c == File(raiz, "Android/data").absolutePath ||
                    c == File(raiz, "Android/obb").absolutePath ||
                    c == lixeira.absolutePath ||
                    f.name.contains("Stickers", true)
                if (!ignorar) coletar(f, lista)
            } else if (ehMidia(f)) lista.add(f)
        }
    }

    private fun ano(d: Date): Int = Calendar.getInstance().apply { time = d }.get(Calendar.YEAR)

    fun dataDe(f: File): Date {
        val ext = f.extension.lowercase()
        try {
            if (ext in fotos) {
                val exif = ExifInterface(f)
                val s = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                if (s != null) {
                    val d = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(s)
                    if (d != null && ano(d) > 1980) return d
                }
            } else {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(f.absolutePath)
                    val s = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                    if (s != null && s.length >= 15) {
                        val d = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).parse(s.substring(0, 15))
                        if (d != null && ano(d) > 1980) return d
                    }
                } finally { r.release() }
            }
        } catch (_: Exception) { }
        Regex("(20[0-9]{2})-?([0-9]{2})-?([0-9]{2})").find(f.name)?.let { m ->
            try {
                val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
                fmt.isLenient = false
                val d = fmt.parse(m.groupValues[1] + m.groupValues[2] + m.groupValues[3])
                if (d != null) return d
            } catch (_: Exception) { }
        }
        return Date(f.lastModified())
    }

    // Move com segurança: nunca sobrescreve e confere o tamanho
    fun mover(f: File, dest: File): Boolean {
        if (dest.exists()) return false
        val tam = f.length()
        dest.parentFile?.mkdirs()
        if (f.renameTo(dest)) return dest.exists() && dest.length() == tam
        f.copyTo(dest, overwrite = false)
        if (dest.length() == tam) { f.delete(); return true }
        dest.delete()
        return false
    }

    // ---------- Lixeira própria do app ----------
    @Synchronized fun paraLixeira(f: File): Boolean {
        lixeira.mkdirs()
        val nm = File(lixeira, ".nomedia")
        if (!nm.exists()) nm.createNewFile()
        var nome: String
        do { nome = "${System.nanoTime()}_${f.name}" } while (File(lixeira, nome).exists())
        if (!mover(f, File(lixeira, nome))) return false
        indice.appendText(nome + "\t" + f.absolutePath + "\n")
        return true
    }

    fun itensLixeira(): List<Pair<File, String>> {
        if (!indice.exists()) return emptyList()
        return indice.readLines().mapNotNull { l ->
            val p = l.split("\t")
            if (p.size == 2 && File(lixeira, p[0]).exists()) File(lixeira, p[0]) to p[1] else null
        }
    }

    @Synchronized fun restaurar(f: File, original: String): File? {
        val o = File(original)
        var dest = o
        var n = 1
        while (dest.exists()) { dest = File(o.parentFile, "${o.nameWithoutExtension}_$n.${o.extension}"); n++ }
        if (!mover(f, dest)) return null
        tirarDoIndice(setOf(f.name))
        return dest
    }

    @Synchronized fun apagarDeVez(itens: List<File>) {
        itens.forEach { it.delete() }
        tirarDoIndice(itens.map { it.name }.toSet())
    }

    private fun tirarDoIndice(nomes: Set<String>) {
        if (!indice.exists()) return
        val sobra = indice.readLines().filter { it.isNotBlank() && it.substringBefore("\t") !in nomes }
        indice.writeText(sobra.joinToString("") { it + "\n" })
    }

    // ---------- Miniaturas e visualização ----------
    fun miniatura(m: Midia, lado: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            if (m.video) ThumbnailUtils.createVideoThumbnail(m.arquivo, Size(lado, lado), null)
            else ThumbnailUtils.createImageThumbnail(m.arquivo, Size(lado, lado), null)
        } else if (m.video) miniaturaVideoAntiga(m.arquivo)
        else carregarImagem(m.arquivo, lado)
    } catch (_: Exception) { null }

    @Suppress("DEPRECATION")
    private fun miniaturaVideoAntiga(f: File): Bitmap? =
        ThumbnailUtils.createVideoThumbnail(f.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)

    fun carregarImagem(f: File, lado: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= lado && o.outHeight / (s * 2) >= lado) s *= 2
        val b = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val rot = try { ExifInterface(f).rotationDegrees } catch (_: Exception) { 0 }
        if (rot == 0) return b
        val mt = Matrix().apply { postRotate(rot.toFloat()) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, mt, true)
    }

    fun tamanho(b: Long): String =
        if (b < 1048576) "${b / 1024} KB" else String.format(Locale.US, "%.1f MB", b / 1048576.0)

    fun informacoes(m: Midia): String {
        val f = m.arquivo
        val sb = StringBuilder()
        sb.append("Nome: ${f.name}\n")
        sb.append("Pasta: ${f.parent ?: ""}\n")
        sb.append("Tamanho: ${tamanho(f.length())}\n")
        sb.append("Data e hora: ${dataHora(m.data)}\n")
        sb.append("Situação: " + (if (m.migrada) "organizada (verde)" else "ainda não migrada (vermelho)") + "\n")
        try {
            if (m.video) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(f.absolutePath)
                    val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    if (w != null && h != null) sb.append("Resolução: $w x $h\n")
                    if (dur != null) sb.append(String.format(Locale.US, "Duração: %d:%02d\n", dur / 60000, (dur / 1000) % 60))
                } finally { r.release() }
            } else {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, o)
                if (o.outWidth > 0) sb.append("Resolução: ${o.outWidth} x ${o.outHeight}\n")
                val e = ExifInterface(f)
                val cam = listOfNotNull(e.getAttribute(ExifInterface.TAG_MAKE), e.getAttribute(ExifInterface.TAG_MODEL)).joinToString(" ")
                if (cam.isNotBlank()) sb.append("Câmera: $cam\n")
                e.latLong?.let { sb.append(String.format(Locale.US, "Localização: %.5f, %.5f\n", it[0], it[1])) }
            }
        } catch (_: Exception) { }
        return sb.toString()
    }
}
