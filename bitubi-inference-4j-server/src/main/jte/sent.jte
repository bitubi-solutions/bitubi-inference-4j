@import java.util.UUID
@import ao.bitubi.inference4j.server.ui.ViewModels.MessageModel
@param UUID conversationId
@param UUID turnId
@param MessageModel userMessage
@template.message(msg = userMessage, conversationId = conversationId)
@template.answer(conversationId = conversationId, turnId = turnId, html = "")
@template.composerActions(conversationId = conversationId, turnId = turnId, disabled = false)
@template.composerText(disabled = false, placeholder = "Message bi4j...")
<div id="alerts" role="alert" hx-swap-oob="true"></div>
