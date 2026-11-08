@import java.util.UUID
@param UUID conversationId
@param UUID turnId
@param String html
<li class="msg assistant"><div class="who">bi4j</div>
  <article id="turn-${turnId.toString()}" class="body answer" hx-sse:connect="/c/${conversationId.toString()}/turns/${turnId.toString()}/stream" hx-sse:close="done" hx-target="this" hx-swap="innerMorph" aria-busy="true" aria-live="off">@template.answerBody(html = html, footer = null)</article>
</li>
