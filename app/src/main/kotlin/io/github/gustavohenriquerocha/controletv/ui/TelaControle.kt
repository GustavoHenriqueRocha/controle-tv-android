package io.github.gustavohenriquerocha.controletv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gustavohenriquerocha.controletv.ControleViewModel
import io.github.gustavohenriquerocha.controletv.protocolos.Marca
import io.github.gustavohenriquerocha.controletv.protocolos.Recurso
import io.github.gustavohenriquerocha.controletv.protocolos.Tecla

/** Ganho do touchpad: quantos pixels da TV por dp arrastado no celular. */
private const val GANHO = 1.6f

/** Distância (px) de arraste na faixa de rolagem para cada "clique" da roda. */
private const val PASSO_RODA = 60f

@Composable
fun TelaControle(vm: ControleViewModel) {
    var menuTvs by remember { mutableStateOf(false) }
    var adicionando by remember { mutableStateOf(false) }
    var confirmarDesligar by remember { mutableStateOf(false) }
    var texto by remember { mutableStateOf("") }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { menuTvs = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(vm.atual?.nome ?: "Escolha uma TV", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = menuTvs, onDismissRequest = { menuTvs = false }) {
                        if (vm.tvs.isEmpty()) {
                            DropdownMenuItem(text = { Text("Nenhuma TV cadastrada") }, onClick = { menuTvs = false })
                        }
                        vm.tvs.forEach { tv ->
                            DropdownMenuItem(
                                text = { Text(tv.nome) },
                                onClick = {
                                    menuTvs = false
                                    vm.escolher(tv)
                                },
                                trailingIcon = {
                                    TextButton(onClick = {
                                        menuTvs = false
                                        vm.remover(tv)
                                    }) { Text("Remover") }
                                },
                            )
                        }
                    }
                }
                TextButton(onClick = { vm.procurar() }, enabled = !vm.procurando) {
                    Text(if (vm.procurando) "Procurando…" else "Procurar")
                }
                TextButton(onClick = { adicionando = true }) { Text("+ IP") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                val linha = when {
                    vm.status.isNotEmpty() -> vm.status
                    vm.conectado -> "Conectado"
                    vm.atual == null -> "Procure ou adicione uma TV"
                    else -> "Desconectado"
                }
                Text(
                    linha,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (vm.atual != null && !vm.conectado) {
                    TextButton(onClick = { vm.reconectar() }) { Text("Reconectar") }
                }
            }

            if (Recurso.PONTEIRO in vm.recursos) {
                Touchpad(aoMover = vm::mover, aoClicar = vm::clicar, aoRolar = vm::rolar)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                Botao("▲") { vm.tecla(Tecla.CIMA) }
                Spacer(Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Botao("◀") { vm.tecla(Tecla.ESQUERDA) }
                Botao("OK") { vm.tecla(Tecla.OK) }
                Botao("▶") { vm.tecla(Tecla.DIREITA) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                Botao("▼") { vm.tecla(Tecla.BAIXO) }
                Spacer(Modifier.weight(1f))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Botao("Voltar") { vm.tecla(Tecla.VOLTAR) }
                Botao("Home") { vm.tecla(Tecla.HOME) }
                Botao("Menu") { vm.tecla(Tecla.MENU) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Botao("Vol −") { vm.tecla(Tecla.VOLUME_MENOS) }
                Botao("Mudo") { vm.tecla(Tecla.MUDO) }
                Botao("Vol +") { vm.tecla(Tecla.VOLUME_MAIS) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (Recurso.CANAIS in vm.recursos) Botao("Ch −") { vm.tecla(Tecla.CANAL_MENOS) }
                Botao("▶︎") { vm.tecla(Tecla.PLAY) }
                Botao("❚❚") { vm.tecla(Tecla.PAUSA) }
                if (Recurso.CANAIS in vm.recursos) Botao("Ch +") { vm.tecla(Tecla.CANAL_MAIS) }
            }

            if (Recurso.TEXTO in vm.recursos) {
                val enviar = {
                    if (texto.isNotEmpty()) {
                        vm.enviarTexto(texto)
                        texto = ""
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = texto,
                        onValueChange = { texto = it },
                        placeholder = { Text("Texto para um campo aberto na TV") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { enviar() }),
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = enviar) { Text("Enviar") }
                }
            }

            Button(
                onClick = { confirmarDesligar = true },
                enabled = vm.conectado,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Desligar a TV") }
        }
    }

    if (adicionando) {
        DialogoAdicionar(
            aoConfirmar = { nome, marca, host ->
                adicionando = false
                vm.adicionar(nome, marca, host)
            },
            aoCancelar = { adicionando = false },
        )
    }

    if (vm.pedindoCodigo) {
        var codigo by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { vm.cancelarCodigo() },
            title = { Text("Código de pareamento") },
            text = {
                OutlinedTextField(
                    value = codigo,
                    onValueChange = { codigo = it.trim() },
                    label = { Text("Código mostrado na TV") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.informarCodigo(codigo) }, enabled = codigo.isNotEmpty()) { Text("Parear") }
            },
            dismissButton = { TextButton(onClick = { vm.cancelarCodigo() }) { Text("Cancelar") } },
        )
    }

    if (vm.encontradas.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { vm.limparEncontradas() },
            title = { Text("TVs na rede") },
            text = {
                Column {
                    vm.encontradas.forEach { e ->
                        TextButton(onClick = {
                            vm.limparEncontradas()
                            vm.adicionar(e.nome, e.marca, e.host)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("${e.nome}\n${e.marca.descricao}", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.limparEncontradas() }) { Text("Fechar") } },
        )
    }

    if (confirmarDesligar) {
        AlertDialog(
            onDismissRequest = { confirmarDesligar = false },
            title = { Text("Desligar a TV?") },
            text = { Text("A maioria das TVs não liga de novo pela rede; vai precisar do controle original.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmarDesligar = false
                    vm.tecla(Tecla.ENERGIA)
                }) { Text("Desligar") }
            },
            dismissButton = { TextButton(onClick = { confirmarDesligar = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun RowScope.Botao(rotulo: String, aoClicar: () -> Unit) {
    val haptico = LocalHapticFeedback.current
    FilledTonalButton(
        onClick = {
            haptico.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            aoClicar()
        },
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(4.dp),
        modifier = Modifier.weight(1f).height(56.dp),
    ) { Text(rotulo, maxLines = 1) }
}

@Composable
private fun Touchpad(aoMover: (Float, Float) -> Unit, aoClicar: () -> Unit, aoRolar: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(Unit) { detectTapGestures(onTap = { aoClicar() }) }
                .pointerInput(Unit) {
                    detectDragGestures { mudanca, arraste ->
                        mudanca.consume()
                        aoMover(arraste.x / density * GANHO, arraste.y / density * GANHO)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "arraste = mouse · toque = clique",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .width(48.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .pointerInput(Unit) {
                    var acumulado = 0f
                    detectVerticalDragGestures(onDragStart = { acumulado = 0f }) { mudanca, dy ->
                        mudanca.consume()
                        acumulado += dy
                        while (acumulado > PASSO_RODA) {
                            aoRolar(false)
                            acumulado -= PASSO_RODA
                        }
                        while (acumulado < -PASSO_RODA) {
                            aoRolar(true)
                            acumulado += PASSO_RODA
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Text("⇕", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun DialogoAdicionar(aoConfirmar: (String, Marca, String) -> Unit, aoCancelar: () -> Unit) {
    var nome by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var marca by remember { mutableStateOf(Marca.LG_NETCAST) }
    AlertDialog(
        onDismissRequest = aoCancelar,
        title = { Text("Adicionar TV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("IP da TV") },
                    placeholder = { Text("10.0.0.20") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = nome,
                    onValueChange = { nome = it },
                    label = { Text("Nome (opcional)") },
                    singleLine = true,
                )
                Marca.entries.forEach { m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = m == marca, onClick = { marca = m }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = m == marca, onClick = { marca = m })
                        Text(m.descricao + if (m.testado) "" else " · não testado")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { aoConfirmar(nome, marca, host) }, enabled = host.isNotBlank()) { Text("Adicionar") }
        },
        dismissButton = { TextButton(onClick = aoCancelar) { Text("Cancelar") } },
    )
}
