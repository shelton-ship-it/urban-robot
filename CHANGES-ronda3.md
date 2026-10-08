# Ronda 3 — paciência (timeouts longos) e fim dos falsos positivos

Causa: falha/cancelamento tratados como "lista vazia" nos clientes; a API responde 500/503 honestos.

## Android (pixgo-android)
- NOVO data/network/Resilience.kt: patiently(), runCatchingNonCancel(), LoadFailedException, Patience
- CatalogRepository: pedidos pacientes; lança em falha (só 404 de conteúdo = null)
- Explore/Home/MyList/Channels/Watch: estado `failed` (erro + Tentar novamente) ≠ vazio; guarda de resposta mais recente; cancelamento nunca é erro
- ChannelsSource/ChannelsRepository: playlist e gate pacientes (429 do gate NÃO repete)
- PlayerFactory: PatientRetryPolicy (20 retries, teto 6 s); live com timeouts 30/60 s
- NetworkModule: player callTimeout 120 s, inatividade 30 s
- PlayerRepository: handshake até 90 s; PlayerScreen: 12 recuperações; ChannelsPlayerScreen: recuperação + spinner + retry

## Web (frontend_web)
- lib/api.ts: GET com retry/backoff (75 s, 45 s por tentativa); mutações não repetem
- catalog/main/search/mylist: guarda de resposta antiga + estado de falha ≠ "sem conteúdo"
- content/watch: só 404 real = "não encontrado"
- lib/channels-source.ts: playlist paciente
- ShakaPlayer: performECDH paciente (90 s), hls.js 90/60/60 s + 10 retries, fatais voltam a inicializar
- fix: PaymentPlan ganha processor/methods (o upload já usava-os em AuthRepository/PlansScreen e não compilava)

## Ronda 3b — correcções críticas
- Ecrã inteiro: o Media3 notifica o listener do botão quando o ícone é sincronizado por código → alternava outra vez (virava e desvirava). Guarda anti-loop em PlayerScreen e ChannelsPlayerScreen.
- Spinner: estado "à espera de dados" relido do player em cada evento + relógio de 250 ms (buffering, READY sem tocar, seek, pause/play). Canais: spinner nativo desligado (era duplicado).
- Catálogo/Início/Minha Coleção: debounce de 150 ms nas cargas (coalesce reinícios do arranque).
- Google: app/build.gradle.kts passa a assinar o debug com app/pixgo-debug.keystore (SHA-1 estável); "cancelado" já não é silencioso.

## Ronda 5
- Ecrã inteiro (ui/common/Orientation.kt): reescrito. O bug "roda e volta a virar": ao rodar o aparelho o controlador soltava o bloqueio para UNSPECIFIED; com a rotação automática do sistema DESLIGADA isso é retrato, e o ecrã virava de volta (cada flip também reiniciava o estado do player/spinner). Agora FORCED_LANDSCAPE fica em paisagem até sair; só sai ao rodar para retrato se a rotação automática estiver ligada e o aparelho já tiver estado em paisagem (700 ms estável).
- Spinner (PlayerScreen): deteção de "parado" (a tocar mas a posição não anda ≥0,9 s) além de BUFFERING; botões centrais nativos voltam a ser escondidos sempre que o controlador os re-mostra.
- Catálogo: interceptor acrescenta v=2 aos GET /api/catalog* (ignora respostas degradadas que o CDN guardou 24 h).
