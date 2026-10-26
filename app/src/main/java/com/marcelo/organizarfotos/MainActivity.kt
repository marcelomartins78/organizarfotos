package com.marcelo.organizarfotos

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
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
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
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
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.VideoView
import java.io.File
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val VERDE = Color.rgb(46, 160, 67)
    private val VERMELHO = Color.rgb(220, 53, 69)

    private val handler = Handler(Looper.getMainLooper())
    private val trabalho = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private var todas: List<Midia> = emptyList()
    private var filtradas: List<Midia> = emptyList()
    private val linhas = mutableListOf<Any>()
    private val selecionadas = mutableSetOf<String>()
    private var procurando = false
    private var jaProcurou = false
    private var servicoAtivo = false
    private var de: Date? = null
    private var ate: Date? = null
    private val colunas = 4
    private var lado = 200

    private lateinit var info: TextView
    private lateinit var busca: EditText
    private lateinit var botaoDe: Button
    private lateinit var botaoAte: Button
    private lateinit var situacao: Spinner
    private lateinit var adaptador: Adaptador

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lado = (resources.displayMetrics.widthPixels - 32) / colunas
        val tela = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 60, 16, 16) }

        val botoes = LinearLayout(this)
        fun botao(t: String, acao: () -> Unit) {
            botoes.addView(Button(this).apply { text = t; isAllCaps = false; setOnClickListener { acao() } })
        }
        botao("Procurar") { procurar() }
        botao("Mover p/ DCIM") { confirmarServico("mover") }
        botao("Renomear") { confirmarServico("renomear") }
        botao("Excluir") { excluirSelecionadas() }
        botao("Lixeira") { abrirLixeira() }
        botao("Limpar seleção") { selecionadas.clear(); atualizarLista() }
        tela.addView(HorizontalScrollView(this).apply { addView(botoes) })

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
            text = "Limpar filtros"; isAllCaps = false
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

        info = TextView(this).apply { textSize = 15f; setPadding(0, 10, 0, 10) }
        tela.addView(info)

        adaptador = Adaptador()
        val lista = ListView(this)
        lista.adapter = adaptador
        lista.divider = null
        tela.addView(lista, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(tela)

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

    override fun onDestroy() {
        handler.removeCallbacks(monitor)
        trabalho.shutdownNow()
        super.onDestroy()
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
        info.text = "Procurando arquivos..."
        Thread {
            val arquivos = mutableListOf<File>()
            Ferramentas.coletar(Ferramentas.raiz, arquivos)
            val total = arquivos.size
            val res = ArrayList<Midia>(total)
            for ((k, f) in arquivos.withIndex()) {
                if (k % 25 == 0) runOnUiThread { info.text = "Lendo datas: $k de $total" }
                res.add(Midia(f, Ferramentas.dataDe(f)))
            }
            runOnUiThread {
                todas = res
                procurando = false
                aplicarFiltros()
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
        linhas.clear()
        filtradas.groupBy { Ferramentas.dia(it.data) }.forEach { (dia, itens) ->
            linhas.add(Cabecalho(dia, itens.size, itens.all { it.migrada }))
            itens.chunked(colunas).forEach { linhas.add(Linha(it)) }
        }
        atualizarLista()
    }

    private fun atualizarLista() {
        val verdes = todas.count { it.migrada }
        info.text = "Total: ${todas.size} | Verdes: $verdes | Vermelhas: ${todas.size - verdes}\n" +
            "Mostrando: ${filtradas.size} | Selecionadas: ${selecionadas.size}"
        adaptador.notifyDataSetChanged()
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
                (v.getChildAt(1) as TextView).text =
                    "${formatarDia(item.dia)} — ${item.qtd} " + (if (item.qtd == 1) "item" else "itens")
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
        l.addView(View(this), LinearLayout.LayoutParams(14, MATCH))
        l.addView(TextView(this).apply { textSize = 16f; setPadding(14, 4, 0, 4) })
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
        cel.setOnClickListener { if (selecionadas.isNotEmpty()) alternar(caminho) else abrir(m) }
        cel.setOnLongClickListener { alternar(caminho); true }
    }

    private fun alternar(c: String) {
        if (!selecionadas.remove(c)) selecionadas.add(c)
        atualizarLista()
    }

    // ---------- Visualizar ----------
    private fun abrir(m: Midia, naLixeira: Boolean = false) {
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val tela = LinearLayout(this)
        tela.orientation = LinearLayout.VERTICAL
        tela.setBackgroundColor(Color.BLACK)
        val area = FrameLayout(this)
        var video: VideoView? = null
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
                runOnUiThread { iv.setImageBitmap(b) }
            }.start()
        }
        tela.addView(area, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val texto = TextView(this)
        texto.setTextColor(Color.WHITE)
        texto.textSize = 14f
        texto.setPadding(30, 20, 30, 20)
        texto.text = "Carregando informações..."
        Thread {
            val s = Ferramentas.informacoes(m)
            runOnUiThread { texto.text = s }
        }.start()
        val rolagem = ScrollView(this)
        rolagem.addView(texto)
        tela.addView(rolagem, LinearLayout.LayoutParams(MATCH, (resources.displayMetrics.heightPixels * 0.28).toInt()))

        val barra = LinearLayout(this)
        barra.addView(Button(this).apply { text = "Fechar"; isAllCaps = false; setOnClickListener { d.dismiss() } })
        if (!naLixeira) barra.addView(Button(this).apply {
            text = "Excluir"; isAllCaps = false
            setOnClickListener { confirmar("Mandar para a lixeira do app?") { d.dismiss(); mandarParaLixeira(listOf(m)) } }
        })
        tela.addView(barra)
        d.setOnDismissListener { video?.stopPlayback() }
        d.setContentView(tela)
        d.show()
    }

    // ---------- Mover / Renomear ----------
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
        confirmar(msg) {
            ServicoOrganizar.fila = alvo
            ServicoOrganizar.acao = acao
            startForegroundService(Intent(this@MainActivity, ServicoOrganizar::class.java))
        }
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
                    0 -> abrir(Midia(f, Ferramentas.dataDe(f)), true)
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
