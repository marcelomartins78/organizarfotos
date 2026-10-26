package com.marcelo.organizarfotos

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var painel: TextView
    private lateinit var log: TextView
    private lateinit var botao: Button
    private val handler = Handler(Looper.getMainLooper())
    private val atualizar = object : Runnable {
        override fun run() {
            painel.text = ServicoOrganizar.status
            log.text = ServicoOrganizar.log.toString()
            botao.isEnabled = !ServicoOrganizar.rodando
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tela = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 40) }
        botao = Button(this).apply { text = "Organizar fotos e vídeos" }
        painel = TextView(this).apply { textSize = 18f; setPadding(0, 20, 0, 20) }
        log = TextView(this)
        val rolagem = ScrollView(this).apply { addView(log) }
        tela.addView(botao)
        tela.addView(painel)
        tela.addView(rolagem)
        setContentView(tela)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)

        botao.setOnClickListener {
            if (!Environment.isExternalStorageManager()) {
                painel.text = "Libere 'Acesso a todos os arquivos' e toque de novo."
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                startForegroundService(Intent(this, ServicoOrganizar::class.java))
            }
        }
    }

    override fun onResume() { super.onResume(); handler.post(atualizar) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(atualizar) }
}
