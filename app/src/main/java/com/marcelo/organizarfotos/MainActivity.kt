package com.marcelo.organizarfotos

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.MediaController
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.VideoView
import java.io.File
import java.security.SecureRandom
import java.util.Calendar
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {

    companion object {
        private var guardadas: List<Midia>? = null
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val VERDE = Color.rgb(46, 160, 67)
    private val VERMELHO = Color.rgb(220, 53, 69)
    private val AZUL = Color.rgb(30, 120, 255)

    private val handler = Handler(Looper.getMainLooper())
    private val trabalho = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private var todas: List<Midia> = emptyList()
    private var filtradas: List<Midia> = emptyList()
    private var visiveis: List<Midia> = emptyList()
    private val linhas = mutableListOf<Any>()
    private val selecionadas = mutableSetOf<String>()
    private var procurando = false
    private var jaProcurou = false
    private var servicoAtivo = false
    private var de: Date? = null
    private var ate: Date? = null
    private var grupos: List<List<Midia>>? = null
    private var tipoRepetidas = ""
    private var pastaAberta: File? = null
    private val colunas = 4
    private var lado = 200

    private lateinit var info: TextView
    private lateinit var busca: EditText
    private lateinit var botaoDe: Button
    private lateinit var botaoAte: Button
    private lateinit var situacao: Spinner
    private lateinit var adaptador: Adaptador
    private lateinit var lista: ListView
    private lateinit var faixa: LinearLayout
    private lateinit var faixaTexto: TextView

    // Gesto secreto: puxar para baixo no topo e segurar 2 segundos
    private var toqueY = -1f
    private var topoNoInicio = false
    private var puxando = false
    private val abrirCofre = Runnable { puxando = false; pedirPin { painelOcultas() } }

    private val monitor = object : Runnable {
        override fun run() {
            if (ServicoOrganizar.rodando) {
                servicoAtivo = true
                info.text = ServicoOrganizar.status
            } else if (servicoAtivo) {
                servicoAtivo = false
                selecionadas.clear()
                val l = ServicoOrganizar.log.toString()
                aviso(ServicoOrganizar.status + (if (l.isNotBlank()) "\n\nDetalhes:\n" + l.take(3000) else ""))
                procurar()
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun prefs(): SharedPreferences = getSharedPreferences("config", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        val tema = prefs().getInt("tema", 0)
        val noite = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val escuro = when (tema) { 1 -> false; 2 -> true; else -> noite }
        setTheme(if (escuro) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        lado = (resources.displayMetrics.widthPixels - 32) / colunas
        val tela = LinearLayout(this)
        tela.orientation = LinearLayout.VERTICAL
        tela.setPadding(16, 60, 16, 16)

        val botoes = LinearLayout(this)
        // Botões com ícone; segurando o dedo aparece o nome
        fun botao(icone: String, nome: String, acao: () -> Unit) {
            botoes.addView(Button(this).apply {
                text = icone; textSize = 20f; isAllCaps = false
                tooltipText = nome; contentDescription = nome
                setOnClickListener { acao() }
            })
        }
        // Só os mais usados ficam à mostra; o resto vai no menu ⋮
        botao("🔍", "Procurar") { procurar() }
        botao("📤", "Encaminhar") { encaminharSelecionadas() }
        botao("🗑️", "Excluir") { excluirSelecionadas() }
        botao("⭐", "Especial") { moverEspecial() }
        val topo = LinearLayout(this)
        topo.setGravity(Gravity.CENTER_VERTICAL)
        topo.addView(HorizontalScrollView(this).apply { addView(botoes) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        val tresPontos = Button(this).apply {
            text = "⋮"; textSize = 22f; isAllCaps = false
            tooltipText = "Mais opções"; contentDescription = "Mais opções"
        }
        tresPontos.setOnClickListener { menuMais(tresPontos) }
        topo.addView(tresPontos)
        tela.addView(topo)

        busca = EditText(this)
        busca.hint = "Filtrar por nome"
        busca.setSingleLine(true)
        busca.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { aplicarFiltros() }
        })
        tela.addView(busca)

        val filtros = LinearLayout(this)
        botaoDe = Button(this).apply { text = "De: —"; isAllCaps = false; setOnClickListener { escolherData(true) } }
        botaoAte = Button(this).apply { text = "Até: —"; isAllCaps = false; setOnClickListener { escolherData(false) } }
        situacao = Spinner(this)
        situacao.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Todas", "Só vermelhas", "Só verdes"))
        situacao.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { aplicarFiltros() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        val limpar = Button(this).apply {
            text = "🧹"; textSize = 20f; isAllCaps = false
            tooltipText = "Limpar filtros"; contentDescription = "Limpar filtros"
            setOnClickListener {
                busca.setText(""); de = null; ate = null
                botaoDe.text = "De: —"; botaoAte.text = "Até: —"
                situacao.setSelection(0)
                aplicarFiltros()
            }
        }
        filtros.addView(botaoDe)
        filtros.addView(botaoAte)
        filtros.addView(situacao)
        filtros.addView(limpar)
        tela.addView(HorizontalScrollView(this).apply { addView(filtros) })

        faixa = LinearLayout(this)
        faixa.setGravity(Gravity.CENTER_VERTICAL)
        faixa.setPadding(16, 4, 16, 4)
        faixa.setBackgroundColor(Color.argb(60, 30, 120, 255))
        faixa.visibility = View.GONE
        faixaTexto = TextView(this)
        faixaTexto.textSize = 15f
        faixa.addView(faixaTexto, LinearLayout.LayoutParams(0, WRAP, 1f))
        faixa.addView(Button(this).apply { text = "✖"; textSize = 20f; tooltipText = "Sair"; contentDescription = "Sair"; setOnClickListener { sairModo() } })
        tela.addView(faixa)

        info = TextView(this).apply { textSize = 15f; setPadding(0, 10, 0, 10) }
        tela.addView(info)

        adaptador = Adaptador()
        lista = ListView(this)
        lista.adapter = adaptador
        lista.divider = null
        tela.addView(lista, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(tela)

        guardadas?.let { todas = it; jaProcurou = true }
        aplicarFiltros()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        if (!temAcesso()) pedirAcesso()
        handler.post(monitor)
    }

    override fun onResume() {
        super.onResume()
        if (!jaProcurou && temAcesso()) procurar()
    }

    // Ao sair do app, as pastas ocultas trancam de novo
    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(abrirCofre)
        if (pastaAberta != null) {
            pastaAberta = null
            grupos = null
            selecionadas.clear()
            todas = guardadas ?: emptyList()
            jaProcurou = false
            aplicarFiltros()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(monitor)
        handler.removeCallbacks(abrirCofre)
        trabalho.shutdownNow()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (::lista.isInitialized) gesto(e)
        return super.dispatchTouchEvent(e)
    }

    private fun gesto(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pos = IntArray(2)
                lista.getLocationOnScreen(pos)
                toqueY = if (e.rawY >= pos[1]) e.rawY else -1f
                topoNoInicio = lista.firstVisiblePosition == 0 && (lista.childCount == 0 || lista.getChildAt(0).top >= 0)
                puxando = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (toqueY < 0 || !topoNoInicio) return
                val dy = e.rawY - toqueY
                if (!puxando && dy > 250) { puxando = true; handler.postDelayed(abrirCofre, 2000) }
                else if (puxando && dy < 150) { puxando = false; handler.removeCallbacks(abrirCofre) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                puxando = false
                toqueY = -1f
                handler.removeCallbacks(abrirCofre)
            }
        }
    }

    private fun temAcesso() = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private fun pedirAcesso() {
        info.text = "Permita o acesso a todos os arquivos e volte para o app."
        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
    }

    // ---------- Busca e filtros ----------
    private fun procurar() {
        if (procurando || ServicoOrganizar.rodando) return
        if (!temAcesso()) { pedirAcesso(); return }
        procurando = true
        jaProcurou = true
        val base = pastaAberta
        info.text = "Procurando arquivos..."
        Thread {
            val arquivos = mutableListOf<File>()
            if (base != null) Ferramentas.coletar(base, arquivos) else Ferramentas.raizes().forEach { Ferramentas.coletar(it, arquivos) }
            val total = arquivos.size
            val res = ArrayList<Midia>(total)
            for ((k, f) in arquivos.withIndex()) {
                if (k % 25 == 0) runOnUiThread { info.text = "Lendo datas: $k de $total" }
                res.add(Midia(f, Ferramentas.dataDe(f)))
            }
            runOnUiThread {
                procurando = false
                if (base?.absolutePath == pastaAberta?.absolutePath) {
                    todas = res
                    if (base == null) guardadas = res
                    aplicarFiltros()
                } else procurar()
            }
        }.start()
    }

    private fun aplicarFiltros() {
        val texto = busca.text.toString().trim()
        val ini = de?.time
        val fim = ate?.time?.plus(86_400_000L)
        val sit = situacao.selectedItemPosition
        filtradas = todas.filter { m ->
            (texto.isEmpty() || m.arquivo.name.contains(texto, true)) &&
                (ini == null || m.data.time >= ini) &&
                (fim == null || m.data.time < fim) &&
                (sit <= 0 || (sit == 1 && !m.migrada) || (sit == 2 && m.migrada))
        }.sortedByDescending { it.data }
        if (pastaAberta == null && jaProcurou) guardadas = todas
        montarLinhas()
    }

    private fun montarLinhas() {
        linhas.clear()
        val g = grupos
        if (g != null) {
            g.forEachIndexed { k, itens ->
                linhas.add(Cabecalho("Grupo ${k + 1} — ${itens.size} itens · ${Ferramentas.tamanho(itens[0].arquivo.length())}", itens))
                itens.chunked(colunas).forEach { linhas.add(Linha(it)) }
            }
            visiveis = g.flatten()
        } else {
            filtradas.groupBy { Ferramentas.dia(it.data) }.forEach { (dia, itens) ->
                val t = "${formatarDia(dia)} — ${itens.size} " + (if (itens.size == 1) "item" else "itens")
                linhas.add(Cabecalho(t, itens))
                itens.chunked(colunas).forEach { linhas.add(Linha(it)) }
            }
            visiveis = filtradas
        }
        atualizarLista()
    }

    private fun atualizarLista() {
        val verdes = todas.count { it.migrada }
        info.text = "Total: ${todas.size} | Verdes: $verdes | Vermelhas: ${todas.size - verdes}\n" +
            "Mostrando: ${visiveis.size} | Selecionadas: ${selecionadas.size}"
        val g = grupos
        val pa = pastaAberta
        when {
            g != null -> { faixa.visibility = View.VISIBLE; faixaTexto.text = "Repetidas ($tipoRepetidas): ${g.size} grupo(s)" }
            pa != null -> { faixa.visibility = View.VISIBLE; faixaTexto.text = "🔒 Pasta oculta: ${pa.name}" }
            else -> faixa.visibility = View.GONE
        }
        adaptador.notifyDataSetChanged()
    }

    private fun sairModo() {
        if (grupos != null) {
            grupos = null
            selecionadas.clear()
            montarLinhas()
        } else if (pastaAberta != null) {
            pastaAberta = null
            selecionadas.clear()
            todas = guardadas ?: emptyList()
            aplicarFiltros()
            procurar()
        }
    }

    private fun escolherData(inicio: Boolean) {
        val c = Calendar.getInstance()
        (if (inicio) de else ate)?.let { c.time = it }
        DatePickerDialog(this, { _, a, m, d ->
            val cal = Calendar.getInstance()
            cal.set(a, m, d, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val txt = String.format("%02d/%02d/%04d", d, m + 1, a)
            if (inicio) { de = cal.time; botaoDe.text = "De: $txt" } else { ate = cal.time; botaoAte.text = "Até: $txt" }
            aplicarFiltros()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun formatarDia(d: String): String {
        val p = d.split("-")
        return if (p.size == 3) "${p[2]}/${p[1]}/${p[0]}" else d
    }

    private fun relativo(p: File): String = p.absolutePath.removePrefix(Ferramentas.raiz.absolutePath).ifEmpty { "/" }

    // ---------- Grade de fotos ----------
    private inner class Adaptador : BaseAdapter() {
        override fun getCount() = linhas.size
        override fun getItem(p: Int): Any = linhas[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (linhas[p] is Cabecalho) 0 else 1

        override fun getView(p: Int, convertView: View?, parent: ViewGroup?): View {
            val item = linhas[p]
            if (item is Cabecalho) {
                val v = (convertView as? LinearLayout) ?: novoCabecalho()
                v.getChildAt(0).setBackgroundColor(if (item.tudoVerde) VERDE else VERMELHO)
                (v.getChildAt(1) as TextView).text = item.titulo
                val sel = v.getChildAt(2) as TextView
                val caminhos = item.itens.map { it.arquivo.absolutePath }
                val todosSel = caminhos.all { it in selecionadas }
                sel.text = if (todosSel) "☑ Desmarcar" else "☐ Selecionar"
                sel.setOnClickListener {
                    if (todosSel) selecionadas.removeAll(caminhos.toSet()) else selecionadas.addAll(caminhos)
                    atualizarLista()
                }
                return v
            }
            val linha = item as Linha
            val v = (convertView as? LinearLayout) ?: novaLinha()
            for (i in 0 until colunas) preencher(v.getChildAt(i) as FrameLayout, linha.itens.getOrNull(i))
            return v
        }
    }

    private fun novoCabecalho(): LinearLayout {
        val l = LinearLayout(this)
        l.setPadding(0, 18, 0, 6)
        l.setGravity(Gravity.CENTER_VERTICAL)
        l.addView(View(this), LinearLayout.LayoutParams(14, MATCH))
        l.addView(TextView(this).apply { textSize = 16f; setPadding(14, 4, 0, 4) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        l.addView(TextView(this).apply { textSize = 14f; setTextColor(AZUL); setPadding(20, 10, 10, 10) })
        return l
    }

    private fun novaLinha(): LinearLayout {
        val l = LinearLayout(this)
        repeat(colunas) {
            val cel = FrameLayout(this)
            cel.setPadding(3, 3, 3, 3)
            val img = ImageView(this)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            cel.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
            cel.addView(View(this), FrameLayout.LayoutParams(MATCH, 10, Gravity.BOTTOM))
            val play = TextView(this)
            play.text = "▶"
            play.textSize = 28f
            play.setTextColor(Color.WHITE)
            cel.addView(play, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            val marca = View(this)
            marca.setBackgroundColor(Color.argb(110, 30, 120, 255))
            cel.addView(marca, FrameLayout.LayoutParams(MATCH, MATCH))
            l.addView(cel, LinearLayout.LayoutParams(lado, lado))
        }
        return l
    }

    private fun preencher(cel: FrameLayout, m: Midia?) {
        if (m == null) {
            cel.visibility = View.INVISIBLE
            cel.setOnClickListener(null)
            cel.setOnLongClickListener(null)
            return
        }
        cel.visibility = View.VISIBLE
        val caminho = m.arquivo.absolutePath
        val img = cel.getChildAt(0) as ImageView
        cel.getChildAt(1).setBackgroundColor(if (m.migrada) VERDE else VERMELHO)
        cel.getChildAt(2).visibility = if (m.video) View.VISIBLE else View.GONE
        cel.getChildAt(3).visibility = if (caminho in selecionadas) View.VISIBLE else View.GONE
        img.tag = caminho
        val b = cache.get(caminho)
        if (b != null) {
            img.setImageBitmap(b)
        } else {
            img.setImageDrawable(null)
            img.setBackgroundColor(Color.DKGRAY)
            trabalho.execute {
                if (img.tag != caminho) return@execute
                val bm = Ferramentas.miniatura(m, lado)
                if (bm != null) cache.put(caminho, bm)
                runOnUiThread { if (img.tag == caminho) img.setImageBitmap(bm) }
            }
        }
        cel.setOnClickListener { if (selecionadas.isNotEmpty()) alternar(caminho) else abrir(visiveis, visiveis.indexOf(m)) }
        cel.setOnLongClickListener { alternar(caminho); true }
    }

    private fun alternar(c: String) {
        if (!selecionadas.remove(c)) selecionadas.add(c)
        atualizarLista()
    }

        // ---------- Visualizar (com setinhas) ----------
    private fun abrir(lista: List<Midia>, inicio: Int, naLixeira: Boolean = false) {
        if (lista.isEmpty()) return
        val itens = lista.toMutableList()
        var pos = inicio.coerceIn(0, itens.size - 1)
        var video: VideoView? = null
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val tela = LinearLayout(this)
        tela.orientation = LinearLayout.VERTICAL
        tela.setBackgroundColor(Color.BLACK)
        val area = FrameLayout(this)
        tela.addView(area, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val contador = TextView(this)
        contador.setTextColor(Color.LTGRAY)
        contador.setPadding(30, 10, 30, 0)
        tela.addView(contador)
        val texto = TextView(this)
        texto.setTextColor(Color.WHITE)
        texto.textSize = 14f
        texto.setPadding(30, 10, 30, 10)
        val rolagem = ScrollView(this)
        rolagem.addView(texto)
        tela.addView(rolagem, LinearLayout.LayoutParams(MATCH, (resources.displayMetrics.heightPixels * 0.25).toInt()))

        fun mostrar() {
            video?.stopPlayback()
            video = null
            area.removeAllViews()
            val m = itens[pos]
            contador.text = "${pos + 1} de ${itens.size}"
            if (m.video) {
                val v = VideoView(this)
                val mc = MediaController(this)
                mc.setAnchorView(v)
                v.setMediaController(mc)
                v.setVideoPath(m.arquivo.absolutePath)
                v.setOnPreparedListener { v.start() }
                area.addView(v, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
                video = v
            } else {
                val iv = ImageView(this)
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                area.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
                val tam = resources.displayMetrics.widthPixels
                Thread {
                    val b = try { Ferramentas.carregarImagem(m.arquivo, tam) } catch (_: Exception) { null }
                    runOnUiThread { if (itens.getOrNull(pos) === m) iv.setImageBitmap(b) }
                }.start()
            }
            texto.text = "Carregando informações..."
            Thread {
                val s = Ferramentas.informacoes(m)
                runOnUiThread { if (itens.getOrNull(pos) === m) texto.text = s }
            }.start()
        }

        val barra = LinearLayout(this)
        fun botaoBarra(t: String, acao: () -> Unit) {
            barra.addView(Button(this).apply { text = t; isAllCaps = false; textSize = 20f; setOnClickListener { acao() } },
                LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        botaoBarra("◀") { if (pos > 0) { pos--; mostrar() } }
        botaoBarra("▶") { if (pos < itens.size - 1) { pos++; mostrar() } }
        if (!naLixeira) {
            botaoBarra("📤") { encaminhar(listOf(itens[pos])) }
            botaoBarra("🗑️") {
                confirmar("Mandar para a lixeira do app?") {
                    video?.stopPlayback()
                    video = null
                    val m = itens[pos]
                    mandarParaLixeira(listOf(m))
                    itens.removeAt(pos)
                    if (itens.isEmpty()) d.dismiss()
                    else {
                        if (pos >= itens.size) pos = itens.size - 1
                        mostrar()
                    }
                }
            }
        }
        botaoBarra("✖") { d.dismiss() }
        tela.addView(barra)
        d.setOnDismissListener { video?.stopPlayback() }
        mostrar()
        d.setContentView(tela)
        d.show()
    }

    // ---------- Encaminhar ----------
    private fun encaminharSelecionadas() {
        if (selecionadas.isEmpty()) { aviso("Selecione as fotos primeiro (segure o dedo numa foto)."); return }
        encaminhar(todas.filter { it.arquivo.absolutePath in selecionadas })
    }

    private fun encaminhar(itens: List<Midia>) {
        if (itens.isEmpty()) return
        val uris = ConcurrentHashMap<String, Uri>()
        val faltam = AtomicInteger(itens.size)
        val caminhos = itens.map { it.arquivo.absolutePath }.toTypedArray()
        MediaScannerConnection.scanFile(this, caminhos, null) { path, uri ->
            if (uri != null) uris[path] = uri
            if (faltam.decrementAndGet() == 0) runOnUiThread { compartilhar(itens, caminhos.mapNotNull { uris[it] }) }
        }
    }

    private fun compartilhar(itens: List<Midia>, lista: List<Uri>) {
        if (lista.isEmpty()) { aviso("Não consegui preparar para encaminhar. Itens de pastas ocultas não podem ser encaminhados."); return }
        val tipo = when {
            itens.all { it.video } -> "video/*"
            itens.none { it.video } -> "image/*"
            else -> "*/*"
        }
        val i = if (lista.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, lista[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(lista))
        i.type = tipo
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Encaminhar para"))
    }

    // ---------- Mover / Renomear ----------
    private fun iniciarServico(acao: String, alvo: List<Midia>, destino: File?) {
        if (ServicoOrganizar.rodando) { aviso("Já tem um processo rodando."); return }
        if (alvo.isEmpty()) { aviso("Nada para fazer."); return }
        ServicoOrganizar.fila = alvo
        ServicoOrganizar.acao = acao
        ServicoOrganizar.destino = destino
        startForegroundService(Intent(this, ServicoOrganizar::class.java))
    }

    private fun confirmarServico(acao: String) {
        if (ServicoOrganizar.rodando) { aviso("Já tem um processo rodando."); return }
        val base = if (selecionadas.isNotEmpty()) todas.filter { it.arquivo.absolutePath in selecionadas } else filtradas
        val alvo = base.filter { !it.migrada }
        val nome = if (acao == "mover") "mover" else "renomear"
        if (alvo.isEmpty()) { aviso("Nenhum arquivo vermelho para $nome."); return }
        val msg = if (acao == "mover")
            "Mover ${alvo.size} arquivo(s) para o DCIM, em pastas por data, já renomeando?\n\nAs verdes não são mexidas."
        else
            "Renomear ${alvo.size} arquivo(s) na própria pasta, com data + letras?\n\nAs verdes não são mexidas."
        confirmar(msg) { iniciarServico(acao, alvo, null) }
    }

    private fun moverParaPasta() {
        if (selecionadas.isEmpty()) { aviso("Selecione as fotos primeiro (segure o dedo numa foto)."); return }
        val alvo = todas.filter { it.arquivo.absolutePath in selecionadas }
        AlertDialog.Builder(this)
            .setTitle("Mover ${alvo.size} item(ns) para:")
            .setItems(arrayOf("DCIM, em pastas por data", "Escolher uma pasta...")) { _, i ->
                if (i == 0) {
                    val vermelhas = alvo.filter { !it.migrada }
                    if (vermelhas.isEmpty()) aviso("As selecionadas já estão organizadas.")
                    else confirmar("Mover ${vermelhas.size} item(ns) para o DCIM, em pastas por data, renomeando?") { iniciarServico("mover", vermelhas, null) }
                } else {
                    escolherPasta("Mover aqui", Ferramentas.raiz) { p ->
                        confirmar("Mover ${alvo.size} item(ns) para ${relativo(p)}, renomeando com data + letras?") { iniciarServico("pasta", alvo, p) }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // Menu dos três pontinhos (canto superior direito)
    private fun menuMais(ancora: View) {
        val opcoes: List<Pair<String, () -> Unit>> = listOf(
            "📥  Mover p/ DCIM" to { confirmarServico("mover") },
            "📂  Mover para..." to { moverParaPasta() },
            "✏️  Renomear" to { confirmarServico("renomear") },
            "👥  Repetidas" to { menuRepetidas() },
            "♻️  Lixeira" to { abrirLixeira() },
            "🌓  Tema" to { escolherTema() },
            "❎  Limpar seleção" to { selecionadas.clear(); atualizarLista() }
        )
        val p = PopupMenu(this, ancora)
        opcoes.forEachIndexed { i, o -> p.menu.add(0, i, i, o.first) }
        p.setOnMenuItemClickListener { opcoes[it.itemId].second(); true }
        p.show()
    }

    // Botão Especial: manda as selecionadas para DCIM/nudes, renomeando com data + letras
    private fun moverEspecial() {
        if (selecionadas.isEmpty()) { aviso("Selecione as fotos primeiro (segure o dedo numa foto)."); return }
        val alvo = todas.filter { it.arquivo.absolutePath in selecionadas }
        val pasta = File(Ferramentas.dcim, "nudes")
        if (!pasta.exists() && !pasta.mkdirs()) { aviso("Não consegui criar a pasta DCIM/nudes."); return }
        confirmar("Mover ${alvo.size} item(ns) para DCIM/nudes, renomeando com data + letras?") { iniciarServico("pasta", alvo, pasta) }
    }

    private fun escolherPasta(botao: String, atual: File, aoEscolher: (File) -> Unit) {
        val subs = Ferramentas.subpastas(atual)
        val ehRaiz = atual.absolutePath == Ferramentas.raiz.absolutePath
        val nomes = mutableListOf<String>()
        if (!ehRaiz) nomes.add("⬆ Voltar")
        subs.forEach { nomes.add("📁 " + it.name) }
        AlertDialog.Builder(this)
            .setTitle("📂 " + relativo(atual))
            .setItems(nomes.toTypedArray()) { _, i ->
                if (!ehRaiz && i == 0) escolherPasta(botao, atual.parentFile ?: Ferramentas.raiz, aoEscolher)
                else escolherPasta(botao, subs[if (ehRaiz) i else i - 1], aoEscolher)
            }
            .setPositiveButton(botao) { _, _ -> aoEscolher(atual) }
            .setNeutralButton("Nova pasta") { _, _ -> novaPasta(atual) { nova -> escolherPasta(botao, nova, aoEscolher) } }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun novaPasta(pai: File, depois: (File) -> Unit) {
        val campo = EditText(this)
        campo.hint = "nome da pasta"
        campo.setSingleLine(true)
        AlertDialog.Builder(this)
            .setTitle("Nova pasta em " + relativo(pai))
            .setView(caixa(campo))
            .setPositiveButton("Criar") { _, _ ->
                val nome = campo.text.toString().trim()
                if (nome.isEmpty() || nome.contains("/") || nome.startsWith(".")) { aviso("Nome inválido."); return@setPositiveButton }
                val nova = File(pai, nome)
                if (nova.exists() || nova.mkdirs()) depois(nova) else aviso("Não consegui criar a pasta.")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Repetidas ----------
    private fun menuRepetidas() {
        val opcoes = mutableListOf("Arquivos idênticos", "Fotos parecidas")
        if (grupos != null) opcoes.add("Sair das repetidas")
        AlertDialog.Builder(this)
            .setTitle("Procurar repetidas em ${filtradas.size} itens")
            .setItems(opcoes.toTypedArray()) { _, i ->
                when (i) {
                    0 -> buscarIdenticas()
                    1 -> buscarParecidas()
                    else -> sairModo()
                }
            }
            .show()
    }

    private fun buscarIdenticas() {
        if (procurando) return
        procurando = true
        val base = filtradas
        Thread {
            val candidatos = base.groupBy { it.arquivo.length() }.values.filter { it.size > 1 }
            val total = candidatos.sumOf { it.size }
            var feitos = 0
            val res = mutableListOf<List<Midia>>()
            for (g in candidatos) {
                val porHash = g.groupBy { m ->
                    feitos++
                    val f = feitos
                    if (f % 10 == 0) runOnUiThread { info.text = "Comparando arquivos: $f de $total" }
                    try { Ferramentas.md5(m.arquivo) } catch (_: Exception) { m.arquivo.absolutePath }
                }
                res.addAll(porHash.values.filter { it.size > 1 })
            }
            runOnUiThread { procurando = false; mostrarRepetidas(res, "idênticas") }
        }.start()
    }

    private fun buscarParecidas() {
        if (procurando) return
        procurando = true
        val base = filtradas
        Thread {
            val fotos = base.filter { !it.video }
            val hashes = ArrayList<Pair<Midia, Long>>()
            for ((k, m) in fotos.withIndex()) {
                if (k % 10 == 0) runOnUiThread { info.text = "Analisando fotos: $k de ${fotos.size}" }
                try { Ferramentas.dHash(m.arquivo)?.let { hashes.add(m to it) } } catch (_: Exception) { }
            }
            val usado = BooleanArray(hashes.size)
            val res = mutableListOf<List<Midia>>()
            for (i in hashes.indices) {
                if (usado[i]) continue
                usado[i] = true
                val g = mutableListOf(hashes[i].first)
                for (j in i + 1 until hashes.size) {
                    if (!usado[j] && java.lang.Long.bitCount(hashes[i].second xor hashes[j].second) <= 8) {
                        g.add(hashes[j].first)
                        usado[j] = true
                    }
                }
                if (g.size > 1) res.add(g)
            }
            runOnUiThread { procurando = false; mostrarRepetidas(res, "parecidas") }
        }.start()
    }

    private fun mostrarRepetidas(res: List<List<Midia>>, tipo: String) {
        if (res.isEmpty()) { aviso("Nenhuma repetida encontrada."); atualizarLista(); return }
        grupos = res
        tipoRepetidas = tipo
        selecionadas.clear()
        montarLinhas()
        aviso("Achei ${res.size} grupo(s). Em cada grupo, selecione as que quer apagar e toque em Excluir. Para voltar, toque em Sair.")
    }

    // ---------- Excluir e lixeira ----------
    private fun excluirSelecionadas() {
        if (selecionadas.isEmpty()) { aviso("Segure o dedo numa foto para selecionar e depois toque em Excluir."); return }
        val alvo = todas.filter { it.arquivo.absolutePath in selecionadas }
        confirmar("Mandar ${alvo.size} item(ns) para a lixeira do app?") { mandarParaLixeira(alvo) }
    }

    private fun mandarParaLixeira(alvo: List<Midia>) {
        info.text = "Mandando ${alvo.size} item(ns) para a lixeira..."
        Thread {
            val feitos = mutableSetOf<String>()
            var falhas = 0
            for (m in alvo) {
                val c = m.arquivo.absolutePath
                try { if (Ferramentas.paraLixeira(m.arquivo)) feitos.add(c) else falhas++ } catch (_: Exception) { falhas++ }
            }
            if (feitos.isNotEmpty()) MediaScannerConnection.scanFile(this@MainActivity, feitos.toTypedArray(), null, null)
            runOnUiThread {
                todas = todas.filter { it.arquivo.absolutePath !in feitos }
                grupos = grupos?.map { g -> g.filter { it.arquivo.absolutePath !in feitos } }?.filter { it.size > 1 }
                selecionadas.removeAll(feitos)
                aplicarFiltros()
                if (falhas > 0) aviso("$falhas item(ns) não puderam ir para a lixeira.")
            }
        }.start()
    }

    private fun abrirLixeira() {
        val itens = Ferramentas.itensLixeira()
        if (itens.isEmpty()) { aviso("A lixeira está vazia."); return }
        val nomes = itens.map { (f, o) -> f.name.substringAfter('_') + "\n   de: " + (File(o).parent ?: "") }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Lixeira (${itens.size})")
            .setItems(nomes) { _, i -> opcoesItemLixeira(itens[i].first, itens[i].second) }
            .setPositiveButton("Restaurar tudo") { _, _ -> restaurarItens(itens) }
            .setNeutralButton("Esvaziar") { _, _ ->
                confirmar("Apagar DE VEZ ${itens.size} item(ns)? Não tem volta.") { esvaziar(itens.map { it.first }) }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun opcoesItemLixeira(f: File, original: String) {
        AlertDialog.Builder(this)
            .setTitle(f.name.substringAfter('_'))
            .setItems(arrayOf("Ver", "Restaurar", "Apagar de vez")) { _, i ->
                when (i) {
                    0 -> abrir(listOf(Midia(f, Ferramentas.dataDe(f))), 0, true)
                    1 -> restaurarItens(listOf(f to original))
                    2 -> confirmar("Apagar de vez? Não tem volta.") { esvaziar(listOf(f)) }
                }
            }
            .show()
    }

    private fun restaurarItens(itens: List<Pair<File, String>>) {
        Thread {
            val novos = mutableListOf<String>()
            var falhas = 0
            for ((f, o) in itens) {
                try { val d = Ferramentas.restaurar(f, o); if (d != null) novos.add(d.absolutePath) else falhas++ } catch (_: Exception) { falhas++ }
            }
            if (novos.isNotEmpty()) MediaScannerConnection.scanFile(this@MainActivity, novos.toTypedArray(), null, null)
            runOnUiThread {
                aviso("Restaurados: ${novos.size}" + (if (falhas > 0) "\nFalhas: $falhas" else ""))
                jaProcurou = false
                procurar()
            }
        }.start()
    }

    private fun esvaziar(arquivos: List<File>) {
        Thread {
            Ferramentas.apagarDeVez(arquivos)
            runOnUiThread { aviso("Apagado(s) de vez: ${arquivos.size}") }
        }.start()
    }

    // ---------- Tema ----------
    private fun escolherTema() {
        val atual = prefs().getInt("tema", 0)
        AlertDialog.Builder(this)
            .setTitle("Tema")
            .setSingleChoiceItems(arrayOf("Seguir o celular", "Claro", "Escuro"), atual) { dlg, i ->
                prefs().edit().putInt("tema", i).apply()
                dlg.dismiss()
                recreate()
            }
            .show()
    }

    // ---------- Senha e pastas ocultas ----------
    private fun caixa(vararg vs: View): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(60, 20, 60, 0)
        vs.forEach { l.addView(it) }
        return l
    }

    private fun campoPin(dica: String): EditText {
        val c = EditText(this)
        c.hint = dica
        c.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        c.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(4))
        c.textSize = 24f
        c.setGravity(Gravity.CENTER)
        return c
    }

    private fun gerarCodigo(): String {
        val letras = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (1..8).map { letras[r.nextInt(letras.length)] }.joinToString("")
    }

    private fun pedirPin(depois: () -> Unit) {
        if (prefs().getString("pin", null) == null) { criarPin(depois); return }
        val campo = campoPin("senha")
        AlertDialog.Builder(this)
            .setTitle("🔒 Senha")
            .setView(caixa(campo))
            .setPositiveButton("Entrar") { _, _ ->
                if (Ferramentas.resumo("ofz:" + campo.text.toString()) == prefs().getString("pin", "")) depois()
                else aviso("Senha errada.")
            }
            .setNeutralButton("Esqueci a senha") { _, _ -> recuperar(depois) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun criarPin(depois: () -> Unit) {
        val c1 = campoPin("nova senha")
        val c2 = campoPin("repita a senha")
        AlertDialog.Builder(this)
            .setTitle("Criar senha (4 números)")
            .setView(caixa(c1, c2))
            .setPositiveButton("Salvar") { _, _ ->
                val a = c1.text.toString()
                val b = c2.text.toString()
                if (a.length != 4 || a != b) { aviso("A senha precisa ter 4 números e as duas precisam ser iguais."); return@setPositiveButton }
                val codigo = gerarCodigo()
                prefs().edit()
                    .putString("pin", Ferramentas.resumo("ofz:" + a))
                    .putString("rec", Ferramentas.resumo("rec:" + codigo))
                    .apply()
                AlertDialog.Builder(this)
                    .setTitle("Código de recuperação")
                    .setMessage("Anote e guarde este código em lugar seguro. Ele serve para criar uma senha nova se você esquecer:\n\n${codigo.take(4)}-${codigo.drop(4)}")
                    .setCancelable(false)
                    .setPositiveButton("Já anotei") { _, _ -> depois() }
                    .show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun recuperar(depois: () -> Unit) {
        val campo = EditText(this)
        campo.hint = "código de recuperação"
        campo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        AlertDialog.Builder(this)
            .setTitle("Recuperar senha")
            .setView(caixa(campo))
            .setPositiveButton("Continuar") { _, _ ->
                val c = campo.text.toString().uppercase().replace("-", "").replace(" ", "")
                if (Ferramentas.resumo("rec:" + c) == prefs().getString("rec", "")) criarPin(depois)
                else aviso("Código errado.")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun painelOcultas() {
        val pastas = Ferramentas.pastasOcultas()
        val b = AlertDialog.Builder(this).setTitle("🔒 Pastas ocultas (${pastas.size})")
        if (pastas.isEmpty()) b.setMessage("Nenhuma pasta oculta ainda.")
        else b.setItems(pastas.map { (f, o) -> "🔒 ${f.name}\n   era: " + relativo(File(o).parentFile ?: Ferramentas.raiz) }.toTypedArray()) { _, i ->
            opcoesOculta(pastas[i].first, pastas[i].second)
        }
        b.setPositiveButton("Ocultar uma pasta") { _, _ -> escolherOcultar() }
            .setNeutralButton("Trocar senha") { _, _ -> criarPin { aviso("Senha trocada.") } }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun opcoesOculta(f: File, original: String) {
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(arrayOf("Ver fotos", "Desocultar")) { _, i ->
                if (i == 0) {
                    grupos = null
                    selecionadas.clear()
                    pastaAberta = f
                    procurar()
                } else confirmar("Desocultar “${f.name}”? Ela volta para " + relativo(File(original).parentFile ?: Ferramentas.raiz) + " e aparece de novo na Galeria.") {
                    desocultar(f, original)
                }
            }
            .show()
    }

    private fun escolherOcultar() {
        escolherPasta("Ocultar esta", Ferramentas.raiz) { p ->
            val c = p.absolutePath
            if (c == Ferramentas.raiz.absolutePath || c == Ferramentas.dcim.absolutePath ||
                c.startsWith(File(Ferramentas.raiz, "Android").absolutePath)) {
                aviso("Essa pasta não pode ser ocultada.")
            } else confirmar("Ocultar a pasta “${p.name}” e tudo que tem dentro? Ela some da Galeria e do Google Fotos deste celular.") {
                info.text = "Ocultando..."
                Thread {
                    val antigos = try { Ferramentas.ocultarPasta(p) } catch (_: Exception) { null }
                    if (antigos != null && antigos.isNotEmpty())
                        MediaScannerConnection.scanFile(this@MainActivity, antigos.toTypedArray(), null, null)
                    runOnUiThread {
                        if (antigos == null) aviso("Não consegui ocultar a pasta.")
                        else {
                            aviso("Pasta ocultada.")
                            jaProcurou = false
                            procurar()
                        }
                    }
                }.start()
            }
        }
    }

    private fun desocultar(f: File, original: String) {
        Thread {
            val dest = try { Ferramentas.desocultar(f, original) } catch (_: Exception) { null }
            if (dest != null) {
                val novos = dest.walkTopDown().filter { it.isFile && Ferramentas.ehMidia(it) }.map { it.absolutePath }.toList()
                if (novos.isNotEmpty()) MediaScannerConnection.scanFile(this@MainActivity, novos.toTypedArray(), null, null)
            }
            runOnUiThread {
                if (dest == null) aviso("Não consegui desocultar.")
                else {
                    aviso("Pasta de volta em " + relativo(dest.parentFile ?: Ferramentas.raiz) + ".")
                    if (pastaAberta?.absolutePath == f.absolutePath) pastaAberta = null
                    jaProcurou = false
                    procurar()
                }
            }
        }.start()
    }

    // ---------- Janelas simples ----------
    private fun confirmar(msg: String, acao: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("Sim") { _, _ -> acao() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun aviso(msg: String) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show()
    }
}
