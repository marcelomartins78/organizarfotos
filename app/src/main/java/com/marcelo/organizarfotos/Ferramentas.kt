package com.marcelo.organizarfotos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Environment
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class Midia(val arquivo: File, val data: Date) {
    val video = arquivo.extension.lowercase() in Ferramentas.videos
    val migrada = Ferramentas.estaMigrada(arquivo)
}

class Cabecalho(val titulo: String, val itens: List<Midia>) {
    val tudoVerde = itens.all { it.migrada }
}

class Linha(val itens: List<Midia>)

object Ferramentas {
    val fotos = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif")
    val videos = setOf("mp4", "3gp", "mkv", "mov", "webm")
    val raiz: File = Environment.getExternalStorageDirectory()
    val dcim = File(raiz, "DCIM")
    // Espaço principal + espaço do Dual Apps (Xiaomi), se o sistema deixar ler
    fun raizes(): List<File> = listOf(raiz) + listOf(File("/storage/emulated/999")).filter { it.exists() && it.canRead() }
    val lixeira = File(raiz, ".LixeiraOrganizarFotos")
    val cofre = File(raiz, ".CofreOrganizarFotos")
    private val indice = File(lixeira, "indice.txt")
    private val indiceCofre = File(cofre, "indice.txt")
    private val nomeCerto = Regex("([0-9]{4}-[0-9]{2}-[0-9]{2})_[A-Z]+[.][A-Za-z0-9]+")

    fun dia(d: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)
    fun dataHora(d: Date): String = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(d)

    fun ehMidia(f: File): Boolean {
        val ext = f.extension.lowercase()
        return ext in fotos || ext in videos
    }

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
                    c.endsWith("/Android/data") ||
                    c.endsWith("/Android/obb") ||
                    c == lixeira.absolutePath ||
                    c == cofre.absolutePath ||
                    f.name.contains("Stickers", true) ||
                    ehCache(f)
                if (!ignorar) coletar(f, lista)
            } else if (ehMidia(f)) lista.add(f)
        }
    }

    // Pastas de cache de aplicativos (AliExpress, Shopee etc.) não entram na busca
    private val nomesCache = listOf("cache", "gs_fs", "aliexpress", "shopee", "temp", "tmp")
    fun ehCache(f: File): Boolean = nomesCache.any { f.name.contains(it, true) }

    fun subpastas(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }

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

    // ---------- Índices (lixeira e cofre) ----------
    private fun lerIndice(pasta: File, ind: File): List<Pair<File, String>> {
        if (!ind.exists()) return emptyList()
        return ind.readLines().mapNotNull { l ->
            val p = l.split("\t")
            if (p.size == 2 && File(pasta, p[0]).exists()) File(pasta, p[0]) to p[1] else null
        }
    }

    private fun tirarDoIndice(ind: File, nomes: Set<String>) {
        if (!ind.exists()) return
        val sobra = ind.readLines().filter { it.isNotBlank() && it.substringBefore("\t") !in nomes }
        ind.writeText(sobra.joinToString("") { it + "\n" })
    }

    // ---------- Lixeira ----------
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

    fun itensLixeira(): List<Pair<File, String>> = lerIndice(lixeira, indice)

    @Synchronized fun restaurar(f: File, original: String): File? {
        val o = File(original)
        var dest = o
        var n = 1
        while (dest.exists()) { dest = File(o.parentFile, "${o.nameWithoutExtension}_$n.${o.extension}"); n++ }
        if (!mover(f, dest)) return null
        tirarDoIndice(indice, setOf(f.name))
        return dest
    }

    @Synchronized fun apagarDeVez(itens: List<File>) {
        itens.forEach { it.delete() }
        tirarDoIndice(indice, itens.map { it.name }.toSet())
    }

    // ---------- Pastas ocultas (cofre) ----------
    @Synchronized fun ocultarPasta(dir: File): List<String>? {
        cofre.mkdirs()
        val nm = File(cofre, ".nomedia")
        if (!nm.exists()) nm.createNewFile()
        val antigos = dir.walkTopDown().filter { it.isFile && ehMidia(it) }.map { it.absolutePath }.toList()
        var nome = dir.name
        var n = 1
        while (File(cofre, nome).exists()) { nome = "${dir.name}_$n"; n++ }
        if (!dir.renameTo(File(cofre, nome))) return null
        indiceCofre.appendText(nome + "\t" + dir.absolutePath + "\n")
        return antigos
    }

    fun pastasOcultas(): List<Pair<File, String>> = lerIndice(cofre, indiceCofre)

    // Pasta "nudes" do botão Especial: fica dentro do cofre (oculta e com senha)
    @Synchronized fun pastaEspecial(): File? {
        val p = File(cofre, "nudes")
        if (p.exists()) return p
        val velha = File(dcim, "nudes")
        if (velha.exists()) return if (ocultarPasta(velha) != null) p else null
        cofre.mkdirs()
        val nm = File(cofre, ".nomedia")
        if (!nm.exists()) nm.createNewFile()
        if (!p.mkdirs()) return null
        indiceCofre.appendText("nudes\t" + velha.absolutePath + "\n")
        return p
    }

    @Synchronized fun desocultar(dir: File, original: String): File? {
        val o = File(original)
        var dest = o
        var n = 1
        while (dest.exists()) { dest = File(o.parentFile, "${o.name}_$n"); n++ }
        dest.parentFile?.mkdirs()
        if (!dir.renameTo(dest)) return null
        tirarDoIndice(indiceCofre, setOf(dir.name))
        // Ao desocultar, os arquivos criptografados voltam ao normal
        dest.walkTopDown().filter { it.isFile && it.extension == EXT }.toList().forEach { e ->
            val normal = File(e.parentFile, e.nameWithoutExtension)
            try { descriptografar(e, normal); e.delete() } catch (_: Exception) { normal.delete() }
        }
        return dest
    }

    // ---------- Criptografia da pasta especial (nudes) ----------
    const val EXT = "ofz"
    private var chaveDados: SecretKey? = null

    fun ehEspecial(p: File): Boolean = p.absolutePath == File(cofre, "nudes").absolutePath

    // Chave mestra guardada no chip de segurança do celular (não sai de lá)
    private fun chaveMestra(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("cofreOrganizar", null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder("cofreOrganizar", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return g.generateKey()
    }

    // Chave dos arquivos, guardada trancada pela chave mestra
    @Synchronized private fun chave(): SecretKey {
        chaveDados?.let { return it }
        val arq = File(cofre, ".chave")
        val k: ByteArray
        if (arq.exists()) {
            val b = arq.readBytes()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, chaveMestra(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
            k = c.doFinal(b.copyOfRange(12, b.size))
        } else {
            k = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, chaveMestra())
            cofre.mkdirs()
            arq.writeBytes(c.iv + c.doFinal(k))
        }
        return SecretKeySpec(k, "AES").also { chaveDados = it }
    }

    fun criptografar(f: File, dest: File): Boolean {
        if (dest.exists()) return false
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, chave(), IvParameterSpec(iv))
        dest.parentFile?.mkdirs()
        try {
            dest.outputStream().use { out ->
                out.write(iv)
                CipherOutputStream(out, c).use { co -> f.inputStream().use { it.copyTo(co, 65536) } }
            }
        } catch (e: Exception) { dest.delete(); throw e }
        if (dest.length() != f.length() + 16) { dest.delete(); return false }
        f.delete()
        return true
    }

    fun descriptografar(f: File, dest: File) {
        f.inputStream().use { inp ->
            val iv = ByteArray(16)
            var lidos = 0
            while (lidos < 16) {
                val n = inp.read(iv, lidos, 16 - lidos)
                if (n < 0) throw java.io.IOException("arquivo incompleto")
                lidos += n
            }
            val c = Cipher.getInstance("AES/CTR/NoPadding")
            c.init(Cipher.DECRYPT_MODE, chave(), IvParameterSpec(iv))
            dest.parentFile?.mkdirs()
            dest.outputStream().use { out -> CipherInputStream(inp, c).use { it.copyTo(out, 65536) } }
        }
    }

    // Criptografa o que ainda estiver aberto dentro da pasta nudes
    fun trancarPendentes(): Int {
        var n = 0
        File(cofre, "nudes").listFiles()?.filter { it.isFile && ehMidia(it) }?.forEach {
            try { if (criptografar(it, File(it.path + "." + EXT))) n++ } catch (_: Exception) { }
        }
        return n
    }

    // ---------- Senha e repetidas ----------
    private fun hex(b: ByteArray): String = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    fun resumo(texto: String): String = hex(MessageDigest.getInstance("SHA-256").digest(texto.toByteArray()))

    fun md5(f: File): String {
        val md = MessageDigest.getInstance("MD5")
        f.inputStream().use { inp ->
            val buf = ByteArray(65536)
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    private fun cinza(c: Int): Int = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    fun dHash(f: File): Long? {
        val b = carregarImagem(f, 64) ?: return null
        val p = Bitmap.createScaledBitmap(b, 9, 8, true)
        var h = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (cinza(p.getPixel(x, y)) > cinza(p.getPixel(x + 1, y))) h = h or (1L shl bit)
            bit++
        }
        if (p != b) p.recycle()
        b.recycle()
        return h
    }

    // ---------- Miniaturas e visualização ----------
    fun miniatura(m: Midia, lado: Int): Bitmap? = try {
        if (m.video) miniaturaVideo(m.arquivo, lado)
        else if (Build.VERSION.SDK_INT >= 29) ThumbnailUtils.createImageThumbnail(m.arquivo, Size(lado, lado), null)
        else carregarImagem(m.arquivo, lado)
    } catch (_: Exception) { null }

    // Pega um quadro do vídeo que não seja preto (tenta 1s, 1/3, metade, 3s)
    fun miniaturaVideo(f: File, lado: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val tempos = listOf(1000L, dur / 3, dur / 2, 3000L).filter { it in 0..dur }.distinct().ifEmpty { listOf(0L) }
            var melhor: Bitmap? = null
            for (t in tempos) {
                val q = quadro(r, t * 1000, lado) ?: continue
                melhor = q
                if (!escura(q)) break
            }
            return melhor
        } catch (_: Exception) {
            return null
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }

    private fun quadro(r: MediaMetadataRetriever, us: Long, lado: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, lado, lado)
        else r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

    private fun escura(b: Bitmap): Boolean {
        var soma = 0L
        var n = 0
        val px = maxOf(1, b.width / 10)
        val py = maxOf(1, b.height / 10)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) { soma += cinza(b.getPixel(x, y)); n++; x += px }
            y += py
        }
        return n == 0 || soma / n < 25
    }

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
