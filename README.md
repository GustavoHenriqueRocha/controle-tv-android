# controle-tv-android

![build](https://github.com/GustavoHenriqueRocha/controle-tv-android/actions/workflows/build.yml/badge.svg)
![Kotlin](https://img.shields.io/badge/kotlin-2.1-7F52FF)
![Android](https://img.shields.io/badge/android-8.0%2B-3DDC84)

Controle remoto de TV pela rede Wi-Fi, direto do celular. Sem PC no meio, sem servidor, sem nuvem: o app fala com a TV pelo protocolo que ela mesma expõe na rede local.

Uma tela só: touchpad (o ponteiro da TV, como o Magic Remote), direcional, OK, voltar, home, volume, canal, teclado para campos de texto e a lista de TVs da casa. Cada marca entra por um **tradutor**, um driver pequeno atrás de uma interface comum.

## TVs suportadas

| TV | Protocolo | Porta | Pareamento | Ponteiro | Texto | Status |
|---|---|---|---|---|---|---|
| LG NetCast 2013 | UDAP (HTTP + XML) | 8080 | código na tela | sim | sim | **testado** |
| LG NetCast 2012 | ROAP (HTTP + XML) | 8080 | código na tela | sim | não | não testado |
| LG webOS 2014+ | SSAP (WebSocket + JSON) | 3001 / 3000 | aceitar na TV | sim | sim | não testado |
| Samsung Tizen 2016+ | `ms.remote.control` (WebSocket) | 8002 / 8001 | aceitar na TV | sim | sim | não testado |
| Roku / Roku TV | ECP (HTTP) | 8060 | nenhum | não | sim | não testado |

**Testado** quer dizer usado em aparelho real: uma LG NetCast 4.0 de 2013. As outras marcas foram implementadas pela documentação pública dos protocolos e pelo comportamento de bibliotecas abertas, e são exercitadas nos testes contra TVs falsas locais. Funcionam no papel; no aparelho, pode faltar um detalhe. Relatos (funcionou ou não, com modelo e ano) são bem-vindos nas issues.

Pontos conhecidos de incerteza nos não testados:

- **NetCast 2012 (ROAP):** os nomes das tags XML do comando seguem a documentação antiga da LG. Muitas TVs 2013 também aceitam ROAP.
- **webOS:** o registro usa o manifesto público que as bibliotecas abertas usam. Firmwares recentes só aceitam `wss://` na 3001 (com certificado autoassinado); o app tenta 3001 e cai para 3000. O sinal da rolagem (`dy`) do pointer socket não foi conferido.
- **Samsung:** modelos 2018+ exigem `wss://` na 8002 e token; 2016–2017 usam `ws://` na 8001 sem token. O app tenta nessa ordem. O formato de ponteiro e texto varia entre anos de firmware.
- **Roku:** aparelhos recentes vêm com "Controle por apps móveis" limitado; é preciso liberar em Configurações > Sistema > Configurações avançadas do sistema.
- **Descoberta SSDP:** testada só contra um respondedor falso local. Para LG NetCast a resposta depende do firmware; se não aparecer, adicione pelo IP.

## Instalar

O APK de debug sai do CI a cada push:

1. Abra a aba **Actions** do repositório, entre no último build verde e baixe o artefato `controle-tv-debug`.
2. Descompacte e copie o `.apk` para o celular (Android 8.0 ou mais novo).
3. Abra o arquivo no celular e permita "instalar apps desconhecidos" para o gerenciador de arquivos.

Para compilar na máquina: JDK 17 e Android SDK (`ANDROID_HOME`), depois

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :protocolos:test          # só os drivers, não precisa de Android SDK
```

## Uso

1. Celular e TV na mesma rede.
2. **Procurar** acha as TVs por SSDP. Se não achar, **+ IP**: digite o IP da TV (por exemplo `10.0.0.20`) e escolha a marca.
3. Pareamento, só na primeira vez:
   - **LG NetCast:** a TV mostra um código de 6 dígitos; digite no app.
   - **LG webOS / Samsung:** a TV pergunta se permite o controle; aceite com o controle original.
   - **Roku:** nada.
4. A credencial (chave, `client-key` ou token) fica guardada no celular. Daí em diante o app conecta direto.

O touchpad só aparece nas TVs que têm ponteiro. Arrastar move o mouse, tocar clica, a faixa da direita rola. O campo de texto escreve no campo que estiver aberto na TV (busca do YouTube, senha do Wi-Fi) e confirma.

## Arquitetura

```text
app/                    Android, Jetpack Compose
  TelaControle.kt         a tela única
  ControleViewModel.kt    fila de comandos, ponteiro acumulado, reconexão
  Armazem.kt              TVs salvas (SharedPreferences, JSON)
        │
        ▼
protocolos/             Kotlin/JVM puro, sem Android
  Tradutor.kt             interface comum + Tecla, Marca, Recurso, Pareamento
  LgNetCast.kt            UDAP (2013) e ROAP (2012)
  LgWebOs.kt              SSAP + pointer socket
  SamsungTizen.kt         ms.remote.control
  Roku.kt                 ECP
  Descoberta.kt           SSDP (M-SEARCH multicast)
  Rede.kt, CanalWs.kt     HTTP e WebSocket (OkHttp)
```

O módulo `protocolos` não conhece Android. Por isso os drivers rodam e são testados em qualquer JVM, e a mesma biblioteca serviria para uma versão desktop ou linha de comando.

A interface que todo tradutor implementa:

```kotlin
interface Tradutor : AutoCloseable {
    val marca: Marca
    val recursos: Set<Recurso>                     // PONTEIRO, TEXTO, CANAIS
    fun conectar(credencial: String?): Pareamento  // Pronto(credencial) ou PedirCodigo
    fun tecla(tecla: Tecla)                        // CIMA, OK, VOLTAR, VOLUME_MAIS...
    fun moverPonteiro(dx: Int, dy: Int)
    fun clicar()
    fun rolar(paraCima: Boolean)
    fun enviarTexto(valor: String)
}
```

Decisões de projeto:

- **Chamadas bloqueantes, uma fila só.** Os drivers são síncronos; o app roda tudo numa thread dedicada, então as teclas chegam na TV na ordem em que foram apertadas.
- **Ponteiro acumulado.** O arraste soma deslocamentos e o app envia um movimento a cada 50 ms, em vez de um pedido por evento de toque. A TV não engasga e o ponteiro não fica para trás.
- **Reconexão automática.** Se um comando falha, o app reconecta uma vez com a credencial guardada e repete. A NetCast, que esquece o pareamento sozinha, é pareada de novo a cada 10 minutos e sempre que recusa um comando.
- **Certificado autoassinado só para a TV.** As portas seguras de webOS e Samsung usam certificado autoassinado; o cliente que aceita esse certificado é usado só para a conexão com a TV escolhida na rede local.

## Adicionar uma marca

1. Crie `protocolos/src/main/kotlin/.../MinhaMarca.kt` implementando `Tradutor`. Construtor com `host` e a porta como parâmetro, para o teste poder apontar para um servidor local.
2. Mapeie cada `Tecla` para o código da TV. Declare em `recursos` só o que ela realmente faz; a tela esconde o resto.
3. Se a TV pede pareamento, devolva `Pareamento.PedirCodigo` (código digitado no app) ou espere a aceitação na TV e devolva `Pareamento.Pronto(credencial)`.
4. Adicione a entrada em `Marca` (com `testado = false`) e em `Tradutores.criar`.
5. Se a TV responde a SSDP, inclua o `ST` em `Ssdp.BUSCAS` e a regra em `Ssdp.interpretar`.
6. Escreva o teste com uma TV falsa: `MockWebServer` para HTTP, `withWebSocketUpgrade` para WebSocket. Veja `LgWebOsTest` como modelo.
7. Atualize a tabela acima. Só marque **testado** depois de usar num aparelho real.

## Testes

```bash
./gradlew --no-daemon :protocolos:test
```

Cada driver é testado contra uma TV falsa que roda no próprio teste, em `127.0.0.1`:

- **NetCast:** pedido de código, chave certa e errada, código das teclas, ponteiro, escape de XML no texto, novo pareamento quando a TV esquece a sessão e a cada 10 minutos.
- **webOS:** registro com e sem `client-key`, recusa e prazo esgotado, setas e cliques pelo pointer socket, volume e texto por SSAP.
- **Samsung:** nome em base64 e token na URL, token devolvido na primeira conexão, teclas, ponteiro, texto e acesso negado.
- **Roku:** consulta inicial, `keypress`, texto letra a letra com escape, recusa.
- **SSDP:** classificação das respostas de cada marca e uma busca completa contra um respondedor UDP falso.

O CI (GitHub Actions) roda esses testes e gera o APK debug a cada push.

## Licença

MIT
