package io.pixgo.app.ui.upload

// Intencionalmente vazio. No Android o envio de conteúdo não existe: "Enviar conteúdo"
// só abre ui/modals/PlatformModals.kt → UploadWebOnlyDialog (uploads só na plataforma web).
// O ficheiro fica (vazio) em vez de ser apagado para que um upload/cópia por cima do
// repositório — que não remove ficheiros — nunca reintroduza a versão antiga.
