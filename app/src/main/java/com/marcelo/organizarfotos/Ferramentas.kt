package com.marcelo.organizarfotos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Environment
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class Midia(val arquivo: File, val data: Date) {
    // No cofre o arquivo é "foto.jpg.ofz": o tipo vem da extensão de dentro
    val video = File(Ferramentas.nomeReal(arquivo)).extension.lowercase() in Ferramentas.videos
    val migrada = Ferramentas.estaMigrada(arquivo)
}

class Cabecalho(val titulo: String, val itens: List<Midia>) {
    val tudoVerde = itens.all { it.migrada }
}

class Linha(val itens: List<Midia>)

// Linha de pasta dentro do cofre: abrir subpasta, voltar ou criar nova
class PastaLinha(val texto: String, val acao: () -> Unit)

class Guardada(val mod: Long, val tam: Long, val data: Long)

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

    fun criptografado(f: File): Boolean = f.extension == EXT
    fun nomeReal(f: File): String = if (criptografado(f)) f.nameWithoutExtension else f.name

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
                    f.name.startsWith(".") ||
                    ehCache(f)
                if (!ignorar) coletar(f, lista)
            } else if (ehMidia(f) || criptografado(f)) lista.add(f)
        }
    }

    // Pastas de cache de aplicativos (AliExpress, Shopee etc.) não entram na busca
    private val nomesCache = listOf("cache", "gs_fs", "aliexpress", "shopee", "temp", "tmp")
    fun ehCache(f: File): Boolean = nomesCache.any { f.name.contains(it, true) }

    // Apaga os Status do WhatsApp (somem em 24h de qualquer jeito)
    fun limparStatus(): Int {
        var n = 0
        for (r in raizes()) for (app in listOf("com.whatsapp/WhatsApp", "com.whatsapp.w4b/WhatsApp Business")) {
            File(r, "Android/media/$app/Media/.Statuses").listFiles()?.forEach {
                if (it.isFile && ehMidia(it) && it.delete()) n++
            }
        }
        return n
    }

    // Fotos RECEBIDAS no WhatsApp (sem as enviadas, sem vídeos e sem GIFs), para o detector de nudes
    fun fotosRecebidasWhatsApp(): List<File> {
        val lista = mutableListOf<File>()
        for (r in raizes()) {
            val bases = listOf("Android/media/com.whatsapp/WhatsApp/Media", "Android/media/com.whatsapp.w4b/WhatsApp Business/Media", "WhatsApp/Media")
            for (b in bases) File(r, b).listFiles()?.filter { it.isDirectory && it.name.endsWith("Images") }?.forEach { coletar(it, lista) }
        }
        val agora = System.currentTimeMillis()
        return lista.filter {
            it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "heic", "heif") &&
                !it.absolutePath.contains("/Sent/") && agora - it.lastModified() > 60_000
        }
    }

    // ---------- Detector de nudes (pela cor de pele) ----------
    // Simples: mede quanto da foto é cor de pele. Na dúvida (30% ou mais de pele), conta como nude.
    private fun ehPele(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        if (r <= 95 || g <= 40 || b <= 20 || r <= g || r <= b || abs(r - g) <= 15 || r - minOf(g, b) <= 15) return false
        val cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b
        val cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b
        return cb in 77.0..127.0 && cr in 133.0..173.0
    }

    fun pareceNude(f: File): Boolean {
        // Imagens pequenas (figurinhas, ícones) ficam de fora
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth < 300 || o.outHeight < 300) return false
        val b = carregarImagem(f, 96) ?: return false
        val p = Bitmap.createScaledBitmap(b, 48, 48, true)
        var pele = 0
        for (y in 0 until 48) for (x in 0 until 48) if (ehPele(p.getPixel(x, y))) pele++
        if (p != b) p.recycle()
        b.recycle()
        return pele >= 48 * 48 * 30 / 100
    }

    // ---------- Datas guardadas (para abrir rápido) ----------
    // Guarda a data de cada arquivo; na próxima vez só lê as datas de arquivos novos ou alterados
    fun lerDatas(arq: File): HashMap<String, Guardada> {
        val m = HashMap<String, Guardada>()
        try {
            if (arq.exists()) arq.forEachLine { l ->
                val p = l.split("\t")
                if (p.size == 4) {
                    val mod = p[1].toLongOrNull()
                    val tam = p[2].toLongOrNull()
                    val d = p[3].toLongOrNull()
                    if (mod != null && tam != null && d != null) m[p[0]] = Guardada(mod, tam, d)
                }
            }
        } catch (_: Exception) { }
        return m
    }

    fun salvarDatas(arq: File, lista: List<Midia>) {
        try {
            val tmp = File(arq.path + ".tmp")
            tmp.bufferedWriter().use { w ->
                lista.forEach { m -> val f = m.arquivo; w.write("${f.absolutePath}\t${f.lastModified()}\t${f.length()}\t${m.data.time}\n") }
            }
            tmp.renameTo(arq)
        } catch (_: Exception) { }
    }

    // Monta a lista com as datas guardadas; as que faltam são lidas em paralelo
    fun comDatas(arquivos: List<File>, guardadas: Map<String, Guardada>, progresso: (Int, Int) -> Unit): List<Midia> {
        val res = arrayOfNulls<Midia>(arquivos.size)
        val novos = ArrayList<Int>()
        for ((i, f) in arquivos.withIndex()) {
            val g = guardadas[f.absolutePath]
            if (g != null && g.mod == f.lastModified() && g.tam == f.length()) res[i] = Midia(f, Date(g.data)) else novos.add(i)
        }
        if (novos.isNotEmpty()) {
            val pool = Executors.newFixedThreadPool(4)
            val feitos = AtomicInteger()
            for (i in novos) pool.execute {
                res[i] = Midia(arquivos[i], dataDe(arquivos[i]))
                val n = feitos.incrementAndGet()
                if (n % 25 == 0) progresso(n, novos.size)
            }
            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.DAYS)
        }
        return res.filterNotNull()
    }

    // Ao fechar o app: apaga as pastas vazias do DCIM (de baixo para cima).
    // A pasta Camera e as pastas de sistema (que começam com ponto) ficam.
    fun apagarPastasVazias(dir: File = dcim): Int {
        var n = 0
        dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { p ->
            n += apagarPastasVazias(p)
            if (!p.name.equals("Camera", true) && p.listFiles()?.isEmpty() == true && p.delete()) n++
        }
        return n
    }

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
            } else if (ext in videos) {
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

    // Move uma pasta comum (com tudo dentro) para dentro de uma pasta privada.
    // Devolve a pasta no lugar novo e os caminhos antigos das fotos (para sumirem da Galeria).
    @Synchronized fun moverPastaPara(dir: File, destino: File): Pair<File, List<String>>? {
        val antigos = dir.walkTopDown().filter { it.isFile && ehMidia(it) }.map { it.absolutePath }.toList()
        var nome = dir.name
        var n = 1
        while (File(destino, nome).exists()) { nome = "${dir.name}_$n"; n++ }
        val novo = File(destino, nome)
        destino.mkdirs()
        if (!dir.renameTo(novo)) return null
        return novo to antigos
    }

    fun pastasOcultas(): List<Pair<File, String>> { trocarNomeEspecial(); return lerIndice(cofre, indiceCofre) }

    // Cofre: a pasta Protegida, dentro de .CofreOrganizarFotos (oculta, com senha e criptografada)
    const val ESPECIAL = "Protegida"
    private val especial get() = File(cofre, ESPECIAL)

    // A pasta se chamava "nudes"; troca o nome por um discreto, sem mexer nas fotos
    @Synchronized fun trocarNomeEspecial() {
        val velha = File(cofre, "nudes")
        if (!velha.exists() || especial.exists() || !velha.renameTo(especial)) return
        if (indiceCofre.exists()) indiceCofre.writeText(indiceCofre.readLines().filter { it.isNotBlank() }.joinToString("") { l ->
            (if (l.substringBefore("\t") == "nudes") ESPECIAL + "\t" + File(dcim, ESPECIAL).absolutePath else l) + "\n"
        })
    }

    // Cofre único: as pastas ocultas antigas (sem criptografia) viram subpastas da Protegida.
    // Devolve quantas pastas foram passadas; trancarPendentes criptografa as fotos depois.
    @Synchronized fun juntarNoCofre(): Int {
        trocarNomeEspecial()
        val outras = lerIndice(cofre, indiceCofre).filter { !ehEspecial(it.first) }
        if (outras.isEmpty()) return 0
        val p = pastaEspecial() ?: return 0
        var n = 0
        for ((f, _) in outras) {
            var nome = f.name
            var k = 1
            while (File(p, nome).exists()) { nome = "${f.name}_$k"; k++ }
            if (f.renameTo(File(p, nome))) { tirarDoIndice(indiceCofre, setOf(f.name)); n++ }
        }
        return n
    }

    @Synchronized fun pastaEspecial(): File? {
        trocarNomeEspecial()
        val p = especial
        if (p.exists()) return p
        val velha = File(dcim, "nudes")
        if (velha.exists()) {
            // Versão antiga: a pasta ficava no DCIM; esconde e troca o nome
            if (ocultarPasta(velha) == null) return null
            trocarNomeEspecial()
            return p.takeIf { it.exists() }
        }
        cofre.mkdirs()
        val nm = File(cofre, ".nomedia")
        if (!nm.exists()) nm.createNewFile()
        if (!p.mkdirs()) return null
        indiceCofre.appendText(ESPECIAL + "\t" + File(dcim, ESPECIAL).absolutePath + "\n")
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

    // ---------- Criptografia da pasta protegida ----------
    const val EXT = "ofz"
    private var chaveDados: SecretKey? = null

    fun ehEspecial(p: File): Boolean = p.absolutePath == especial.absolutePath
    // A pasta Protegida ou uma subpasta dela (tudo ali dentro fica criptografado)
    fun dentroEspecial(p: File): Boolean = ehEspecial(p) || p.absolutePath.startsWith(especial.absolutePath + "/")

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
        // Sem isso, fotos sem a data no nome apareceriam com a data de hoje
        dest.setLastModified(dataDe(f).time)
        f.delete()
        return true
    }

    fun descriptografar(f: File, dest: File) {
        dest.parentFile?.mkdirs()
        abrirDecifrado(f).use { inp -> dest.outputStream().use { out -> inp.copyTo(out, 65536) } }
    }

    // Lê o arquivo criptografado já aberto, sem gravar nada no celular
    fun abrirDecifrado(f: File): InputStream {
        val inp = f.inputStream()
        try {
            val iv = ByteArray(16)
            var lidos = 0
            while (lidos < 16) {
                val n = inp.read(iv, lidos, 16 - lidos)
                if (n < 0) throw java.io.IOException("arquivo incompleto")
                lidos += n
            }
            val c = Cipher.getInstance("AES/CTR/NoPadding")
            c.init(Cipher.DECRYPT_MODE, chave(), IvParameterSpec(iv))
            return CipherInputStream(inp, c)
        } catch (e: Exception) { inp.close(); throw e }
    }

    // Vídeo criptografado lido em pedaços (para a miniatura), sem descriptografar o arquivo todo.
    // No modo CTR dá para começar de qualquer ponto: o contador é o IV + número do bloco.
    private class FonteCifrada(f: File, private val chave: SecretKey) : MediaDataSource() {
        private val raf = RandomAccessFile(f, "r")
        private val iv = ByteArray(16).also { raf.readFully(it) }
        private val total = f.length() - 16

        override fun getSize(): Long = total

        @Synchronized override fun readAt(pos: Long, buf: ByteArray, off: Int, size: Int): Int {
            if (pos >= total) return -1
            val n = minOf(size.toLong(), total - pos).toInt()
            if (n <= 0) return 0
            val pulo = (pos % 16).toInt()
            val cont = iv.copyOf()
            var soma = pos / 16
            var i = 15
            while (i >= 0 && soma != 0L) {
                val v = (cont[i].toInt() and 0xff) + (soma and 0xff).toInt()
                cont[i] = v.toByte()
                soma = (soma ushr 8) + (v shr 8)
                i--
            }
            val c = Cipher.getInstance("AES/CTR/NoPadding")
            c.init(Cipher.DECRYPT_MODE, chave, IvParameterSpec(cont))
            val cifrado = ByteArray(pulo + n)
            raf.seek(16 + pos - pulo)
            raf.readFully(cifrado)
            System.arraycopy(c.doFinal(cifrado), pulo, buf, off, n)
            return n
        }

        override fun close() = raf.close()
    }

    // Pasta fixa "Recentes": tudo que entra no cofre sem pasta definida cai nela
    const val RECENTES = "Recentes"

    @Synchronized fun pastaRecentes(): File? = pastaEspecial()?.let { p -> File(p, RECENTES).also { it.mkdirs() } }

    // Arquivos soltos na raiz do cofre vão para Recentes (continuam criptografados, só mudam de pasta)
    @Synchronized fun soltosParaRecentes(): Int {
        val raizCofre = especial
        val soltos = raizCofre.listFiles()?.filter { it.isFile } ?: return 0
        if (soltos.isEmpty()) return 0
        val rec = pastaRecentes() ?: return 0
        var n = 0
        for (f in soltos) {
            var dest = File(rec, f.name)
            var k = 1
            while (dest.exists()) { dest = File(rec, "${k}_${f.name}"); k++ }
            if (f.renameTo(dest)) n++
        }
        return n
    }

    // Tira uma foto do cofre: descriptografa para DCIM/<data da foto>, com o mesmo nome
    @Synchronized fun devolver(f: File, data: Date): File? {
        val pasta = File(dcim, dia(data))
        val o = File(pasta, nomeReal(f))
        var dest = o
        var n = 1
        while (dest.exists()) { dest = File(pasta, "${o.nameWithoutExtension}_$n.${o.extension}"); n++ }
        try { descriptografar(f, dest) } catch (_: Exception) { dest.delete(); return null }
        if (dest.length() != f.length() - 16) { dest.delete(); return null }
        dest.setLastModified(data.time)
        f.delete()
        return dest
    }

    // Criptografa o que ainda estiver aberto dentro da pasta protegida
    fun trancarPendentes(): Int {
        var n = 0
        especial.walkTopDown().filter { it.isFile && ehMidia(it) }.toList().forEach {
            try { if (criptografar(it, File(it.path + "." + EXT))) n++ } catch (_: Exception) { }
        }
        return n
    }

    // Fotos que ficaram em DCIM/nudes (versão antiga do app ou Desocultar antigo):
    // voltam criptografadas para a pasta Protegida. Devolve os caminhos antigos (para sumirem da Galeria).
    @Synchronized fun recolherNudesAntiga(): List<String> {
        val velha = File(dcim, "nudes")
        if (!velha.isDirectory) return emptyList()
        val antes = velha.walkTopDown().filter { it.isFile && ehMidia(it) }.map { it.absolutePath }.toList()
        val p = pastaEspecial() ?: return emptyList()
        if (!velha.exists()) return antes   // a pasta inteira foi para a Protegida; trancarPendentes criptografa
        val feitos = mutableListOf<String>()
        velha.walkTopDown().filter { it.isFile && ehMidia(it) }.toList().forEach { f ->
            val base = File(p, f.relativeTo(velha).path)
            var dest = File(base.path + "." + EXT)
            var n = 1
            while (dest.exists()) { dest = File(base.parentFile, "${base.nameWithoutExtension}_$n.${base.extension}.$EXT"); n++ }
            try { if (criptografar(f, dest)) feitos.add(f.absolutePath) } catch (_: Exception) { }
        }
        // Apaga as pastas que ficaram vazias
        velha.walkBottomUp().filter { it.isDirectory }.forEach { if (it.listFiles()?.isEmpty() == true) it.delete() }
        return feitos
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
        if (criptografado(m.arquivo) && !m.video) imagemCifrada(m.arquivo, lado)
        else if (m.video) miniaturaVideo(m.arquivo, lado)
        else if (Build.VERSION.SDK_INT >= 29) ThumbnailUtils.createImageThumbnail(m.arquivo, Size(lado, lado), null)
        else carregarImagem(m.arquivo, lado)
    } catch (_: Exception) { null }

    // Pega um quadro do vídeo que não seja preto (tenta 1s, 1/3, metade, 3s)
    fun miniaturaVideo(f: File, lado: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            if (criptografado(f)) r.setDataSource(FonteCifrada(f, chave())) else r.setDataSource(f.absolutePath)
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

    // Foto do cofre: descriptografa na memória e já reduz para o tamanho pedido
    fun imagemCifrada(f: File, lado: Int): Bitmap? {
        val bytes = abrirDecifrado(f).use { it.readBytes() }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= lado && o.outHeight / (s * 2) >= lado) s *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val rot = try { ExifInterface(ByteArrayInputStream(bytes)).rotationDegrees } catch (_: Exception) { 0 }
        if (rot == 0) return b
        val mt = Matrix().apply { postRotate(rot.toFloat()) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, mt, true)
    }

    fun tamanho(b: Long): String =
        if (b < 1048576) "${b / 1024} KB" else String.format(Locale.US, "%.1f MB", b / 1048576.0)

    fun informacoes(m: Midia, cores: Boolean = true): String {
        val f = m.arquivo
        val sb = StringBuilder()
        if (criptografado(f)) {
            sb.append("Nome: ${nomeReal(f)}\n")
            sb.append("Pasta: 🔒 ${f.parentFile?.absolutePath?.removePrefix(cofre.absolutePath + "/") ?: ""}\n")
            sb.append("Tamanho: ${tamanho(f.length() - 16)}\n")
            sb.append("Data e hora: ${dataHora(m.data)}\n")
            sb.append("Criptografada no cofre\n")
            return sb.toString()
        }
        sb.append("Nome: ${f.name}\n")
        sb.append("Pasta: ${f.parent ?: ""}\n")
        sb.append("Tamanho: ${tamanho(f.length())}\n")
        sb.append("Data e hora: ${dataHora(m.data)}\n")
        if (cores) sb.append("Situação: " + (if (m.migrada) "organizada (verde)" else "ainda não migrada (vermelho)") + "\n")
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
