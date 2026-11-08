@import java.util.List
@import java.util.UUID
@import ao.bitubi.inference4j.server.ui.ViewModels.SidebarGroup
@param UUID conversationId
@param String html
@param String footer
@param String error
@param List<SidebarGroup> groups
@param String contextLabel
@param boolean contextWarn
@template.answerBody(html = html, footer = footer)
@if(error != null)<div class="alert" role="alert"><p>${error}</p></div>@endif
@template.composerActions(conversationId = conversationId, turnId = null, disabled = false)
<div id="conversation-list" hx-swap-oob="true">@template.sidebarList(groups = groups, query = "")</div>
<span id="context-counter" class="${contextWarn ? "warn" : ""}" hx-swap-oob="true">${contextLabel}</span>
<div id="status-live" class="visually-hidden" role="status" hx-swap-oob="true">${error == null ? "Answer complete" : "Answer failed"}</div>
