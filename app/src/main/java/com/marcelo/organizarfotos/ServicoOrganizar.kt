package com.marcelo.organizarfotos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File

class ServicoOrganizar : Service() {

    companion object {
        @Volatile var rodando = false
        @Volatile var status = ""
        val log = StringBuffer()
        @Volatile var fila: List<Midia> = emptyList()
        @Volatile var acao = "mover"
        @Volatile var destino: File? = null
    }

    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (rodando) return START_NOT_STICKY
        rodando = true
        log.setLength(0)
        status = "Iniciando..."
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("org", "Organizar fotos", NotificationManager.IMPORTANCE_LOW))
        val n = notificacao("Iniciando...", 0, 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
        val wl = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "organizarfotos:trabalho")
        wl.acquire()
        Thread {
            try { processar() } catch (e: Exception) { escrever("Falha geral: ${e.message}") }
            finally {
                wl.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                nm.notify(2, Notification.Builder(this, "org")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Organizar fotos — concluído")
                    .setContentText(status.replace("\n", " | "))
                    .build())
                rodando = false
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

    private fun processar() {
        val tipo = acao
        val pastaDestino = destino
        val cripto = tipo == "pasta" && pastaDestino != null && Ferramentas.ehEspecial(pastaDestino)
        val verbo = if (tipo == "renomear") "Renomeando" else if (cripto) "Protegendo" else "Movendo"
        val lista = fila.sortedBy { it.data }
        val total = lista.size
        val contador = mutableMapOf<String, Int>()
        val caminhos = mutableListOf<String>()
        var ok = 0
        var erros = 0
        var pulados = 0
        for ((idx, m) in lista.withIndex()) {
            val f = m.arquivo
            try {
                val dia = Ferramentas.dia(m.data)
                val jaTemNome = f.name.startsWith(dia + "_") &&
                    f.nameWithoutExtension.substringAfter('_').all { it in 'A'..'Z' }
                val destDir = when (tipo) {
                    "mover" -> File(Ferramentas.dcim, dia)
                    "pasta" -> pastaDestino ?: File(Ferramentas.dcim, dia)
                    else -> f.parentFile ?: Ferramentas.raiz
                }
                val mesmaPasta = f.parentFile?.absolutePath == destDir.absolutePath
                val pular = !f.exists() || (tipo != "pasta" && m.migrada) || (mesmaPasta && jaTemNome)
                if (pular) {
                    pulados++
                } else {
                    destDir.mkdirs()
                    val chave = destDir.absolutePath + "|" + dia
                    var i = contador[chave] ?: 0
                    var dest: File
                    do { dest = File(destDir, "${dia}_${Ferramentas.letras(i)}.${f.extension.lowercase()}"); i++ } while (dest.exists() || File(dest.path + "." + Ferramentas.EXT).exists())
                    contador[chave] = i
                    val origem = f.absolutePath
                    val deuCerto = if (cripto) Ferramentas.criptografar(f, File(dest.path + "." + Ferramentas.EXT)) else Ferramentas.mover(f, dest)
                    if (deuCerto) {
                        caminhos.add(origem)
                        if (!cripto) {
                            dest.setLastModified(m.data.time)
                            caminhos.add(dest.absolutePath)
                        }
                        ok++
                    } else {
                        erros++
                        escrever("Não consegui: $origem")
                    }
                }
            } catch (e: Exception) {
                erros++
                escrever("Erro em ${f.absolutePath}: ${e.message}")
            }
            val feitos = idx + 1
            status = "$verbo: $feitos de $total\nFeitos: $ok | Erros: $erros | Pulados: $pulados\nFaltam: ${total - feitos}"
            if (feitos % 20 == 0 || feitos == total)
                nm.notify(1, notificacao("$feitos de $total — erros: $erros", total, feitos))
        }
        if (cripto) try { Ferramentas.trancarPendentes() } catch (_: Exception) { }
        if (caminhos.isNotEmpty()) MediaScannerConnection.scanFile(this, caminhos.toTypedArray(), null, null)
        status = "CONCLUÍDO.\nTotal: $total | Feitos: $ok | Erros: $erros | Pulados: $pulados"
    }
}
