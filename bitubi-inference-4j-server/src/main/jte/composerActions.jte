@import java.util.UUID
@param UUID conversationId
@param UUID turnId
@param boolean disabled
@param boolean oob
<div id="composer-actions" hx-swap-oob="${oob ? "true" : null}">
  @if(turnId != null)
    <button type="button" id="stop" class="stop" hx-post="/c/${conversationId.toString()}/turns/${turnId.toString()}/stop" hx-swap="none">Stop</button>
  @else
    <button type="submit" id="send" class="send" disabled="${disabled}">Send</button>
  @endif
</div>
