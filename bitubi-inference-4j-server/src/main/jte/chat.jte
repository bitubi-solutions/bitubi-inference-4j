@import ao.bitubi.inference4j.server.ui.ViewModels.ChatModel
@param ChatModel chat
<main id="chat" data-conversation="${chat.id().toString()}">
  @if(chat.banner() != null)
    <p class="banner" role="note">${chat.banner()}</p>
  @endif
  <div id="alerts" role="alert"></div>
  <ol id="messages" class="messages">
    @for(var msg : chat.messages())
      @template.message(msg = msg, conversationId = chat.id())
    @endfor
    @if(chat.running() != null)
      @template.answer(conversationId = chat.id(), turnId = chat.running().turnId(), html = chat.running().htmlSoFar())
    @endif
  </ol>
  @if(chat.empty())
    <section class="starters" aria-label="Suggestions">
      <h2>How can I help?</h2>
      <ul>
        @for(var s : chat.starters())
          <li><button type="button" class="starter" data-starter="${s}">${s}</button></li>
        @endfor
      </ul>
    </section>
  @endif
  <form id="composer" aria-label="Message" hx-post="/c/${chat.id().toString()}/messages" hx-replace-url="/c/${chat.id().toString()}" hx-target="#messages" hx-swap="beforeend">
    @template.composerText(oob = false, disabled = chat.readOnly(), placeholder = chat.readOnly() ? chat.readOnlyReason() : "Message bi4j...")
    @template.composerActions(oob = false, conversationId = chat.id(), turnId = null, disabled = chat.readOnly() || chat.running() != null)
  </form>
  @if(chat.readOnly())
    <p class="readonly-note"><a href="/">New chat</a></p>
  @endif
  <p class="composer-note">Local model: answers may be wrong. <span id="context-counter" class="@if(chat.context().warn())warn@endif">${chat.context().label()}</span></p>
  <div id="status-live" class="visually-hidden" role="status"></div>
</main>
