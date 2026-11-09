@import java.util.UUID
@import ao.bitubi.inference4j.server.ui.ViewModels.MessageModel
@param UUID conversationId
@param UUID turnId
@param MessageModel userMessage
@param String notice
@template.message(msg = userMessage, conversationId = conversationId)
@template.answer(conversationId = conversationId, turnId = turnId, html = "")
@template.composerActions(oob = true, conversationId = conversationId, turnId = turnId, disabled = false)
@template.composerText(oob = true, disabled = false, placeholder = "Message bi4j...")
<div id="alerts" role="alert" hx-swap-oob="true"></div>
<div id="status-live" class="visually-hidden" role="status" hx-swap-oob="true">${notice}</div>
