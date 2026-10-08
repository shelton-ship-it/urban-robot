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
