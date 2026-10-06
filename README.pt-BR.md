# StreamingService

Compartilhe sua tela online e deixe outras pessoas assistirem com um código de sala curto.
Um único código Kotlin (Kotlin Multiplatform + Compose Multiplatform) roda em **Windows, macOS, Linux, Android, iOS e web**,
e um pequeno servidor relay em Kotlin conecta todos, então quem transmite em um dispositivo pode ser assistido em qualquer outro.

🇺🇸 [Read in English](README.md)

## O que dá para fazer

| Plataforma | Compartilhar a tela | Assistir | Saída |
| ---------- | :-----------------: | :------: | ----- |
| Windows    | ✅ | ✅ | `.msi`, `.exe` |
| macOS      | ✅ | ✅ | `.dmg` |
| Linux (X11) | ✅ | ✅ | `.deb`, `.rpm` |
| Android    | ✅ (MediaProjection) | ✅ | `.apk` |
| Web (navegadores desktop) | ✅ (`getDisplayMedia`) | ✅ | site estático, servido pelo relay |
| iOS        | – (exige uma extensão ReplayKit) | ✅ | `.ipa` sem assinatura |

Recursos: códigos de sala e links (`/?room=ABC123`), senha opcional, presets de qualidade,
contagem de espectadores e taxa de bits ao vivo, reconexão automática (quem cai mantém a sala por 20 s),
configurações salvas, interface em inglês e português, e **suporte ao Discord** (avisos ao vivo e Discord Activity).

## Como funciona

```text
 Anfitrião (qualquer plataforma)    Servidor relay (Ktor)             Espectadores (qualquer plataforma)
 captura a tela → JPEG  ──ws──►  /ws/host      /ws/watch/{code}  ──ws──►  decodifica → desenha
                                  salas, distribuição, reenvio do último quadro
                                  serve o app web, publica no Discord
```

Os quadros são imagens JPEG enviadas por WebSocket, o que mantém um único transporte simples funcionando igual em todos os
alvos. O custo em relação ao WebRTC é mais banda e latência (cerca de 0,3 a 1 Mbit/s na qualidade padrão) e ainda não há áudio.
O formato dos dados está em [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Início rápido

Requer JDK 17+ (recomendado 21). Builds Android também precisam do Android SDK (plataforma 37).

```bash
# 1. Inicie o servidor relay (ele também serve o app web, se você o compilou, veja abaixo)
./gradlew :server:run                 # escuta em http://localhost:8080

# 2. Inicie um cliente
./gradlew :desktopApp:run             # app desktop, ou...
./gradlew :webApp:wasmJsBrowserDevelopmentRun   # app web no servidor de desenvolvimento (configure o endereço do servidor)
./gradlew :androidApp:installDebug    # Android (o emulador acessa o servidor em 10.0.2.2:8080)
```

No app, abra **Configurações** e defina o **endereço do servidor** (padrão `localhost:8080`). Depois clique em **Compartilhar minha tela**
em um dispositivo e digite o código da sala em **Assistir a uma transmissão** em outro.

### Rodando o servidor com o app web (Docker)

```bash
./gradlew :server:installDist :webApp:wasmJsBrowserDistribution
docker build -t streamingservice .
docker run -p 8080:8080 streamingservice      # abra http://localhost:8080
```

Para qualquer uso além da sua máquina, coloque o servidor atrás de HTTPS (um proxy reverso como Caddy ou nginx):
os navegadores só permitem captura de tela em origens seguras, e os clientes usam `wss://` para endereços não locais.

### Configuração do servidor

Tudo opcional, via variáveis de ambiente:

| Variável | Padrão | Significado |
| -------- | ------ | ----------- |
| `PORT` | `8080` | Porta em que escuta |
| `PUBLIC_URL` | – | Endereço `https://` público, usado no link dos avisos do Discord |
| `DISCORD_WEBHOOK_URL` | – | Ativa os avisos "X está ao vivo", veja [docs/DISCORD.md](docs/DISCORD.md) |
| `DISCORD_CLIENT_ID` | – | Permite rodar o app web como Discord Activity |
| `STATIC_DIR` | – | Pasta com o app web compilado para servir (a imagem Docker já define) |
| `MAX_ROOMS` | `200` | Salas ao vivo ao mesmo tempo |
| `MAX_VIEWERS_PER_ROOM` | `100` | Espectadores por sala |
| `HOST_GRACE_SECONDS` | `20` | Quanto tempo a sala sobrevive à queda da conexão do anfitrião |

## Discord

- **Avisos:** aponte `DISCORD_WEBHOOK_URL` para o webhook de um canal e marque *Anunciar no Discord* no app.
  Ao iniciar, o servidor publica o código da sala e o link, e edita a mensagem quando você para.
  As mensagens nunca podem marcar `@everyone` ou cargos, e os avisos têm limite de frequência.
- **Activity:** com `DISCORD_CLIENT_ID` definido, o app web roda dentro de uma chamada do Discord. Todos na chamada caem
  na mesma sala automaticamente, protegida por um segredo que só os participantes conhecem.

Os passos de configuração estão em [docs/DISCORD.md](docs/DISCORD.md).

## Estrutura

```text
protocol/     Protocolo compartilhado por clientes e servidor (código de sala, mensagens, formato de quadro)
server/       Servidor relay Ktor (+ anunciante do Discord)
shared/       App Kotlin Multiplatform: UI, cliente de transporte, controladores, captura por plataforma
androidApp/   App Android (a captura MediaProjection fica aqui)
desktopApp/   Ponto de entrada do Compose Desktop + configuração dos instaladores
webApp/       App web Kotlin/Wasm (+ integração com o Discord Embedded App SDK)
iosApp/       Casca SwiftUI em volta da UI compartilhada (projeto Xcode gerado pelo XcodeGen)
assets/icon/  Fonte do ícone; tools/generate-icons.ps1 gera todos os formatos
.github/      CI (build.yml)
```

## Testes

```bash
./gradlew :protocol:jvmTest :server:test :shared:desktopTest
```

Cobrem o protocolo, o relay (reconexão, senhas, limites, mensagens do Discord, arquivos estáticos) e a lógica do app,
incluindo execuções ponta a ponta do cliente real contra o servidor real por um socket.

## CI

O [`.github/workflows/build.yml`](.github/workflows/build.yml) roda os testes e compila Windows, macOS, Linux, Android,
web + servidor (incluindo a imagem Docker) e iOS a cada push e pull request. Os resultados ficam como artefatos do workflow.

Secrets opcionais para um APK Android de release assinado (sem eles é gerado um APK assinado com a chave de debug):
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
Os instaladores de macOS e Windows e o `.ipa` do iOS não são assinados.

## Situação e limites

Verificado no Windows: todos os testes acima, builds de desktop e Android, e o app web rodando no Chrome (uma página
transmitindo para outra página espectadora pelo relay).
Ainda não verificado em hardware real: a captura MediaProjection do Android em execução, os instaladores de Linux/macOS,
o build do iOS (exige um Mac, compilado só no CI) e um Discord Activity de verdade.

Limites: JPEG por WebSocket em vez de WebRTC, sem áudio, sem contas (a sala é protegida pelo código e pela senha opcional),
um monitor no desktop, navegadores de celular não compartilham a tela, e a captura do Android mantém a orientação com que começou.
Um Discord Activity sempre consegue assistir; se consegue *compartilhar* depende da permissão de captura de tela que o Discord dá ao seu iframe.

## Ícone

A fonte é [`assets/icon/icon.svg`](assets/icon/icon.svg). Para regenerar todos os formatos raster (Windows):

```powershell
pwsh tools/generate-icons.ps1
```

## Licença

Veja [LICENSE](LICENSE).
