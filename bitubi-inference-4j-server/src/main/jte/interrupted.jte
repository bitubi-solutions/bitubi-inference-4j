@import java.util.UUID
@param UUID conversationId
@param String retryText
<div class="interrupted" role="alert">
  <p>Answer interrupted.</p>
  @if(retryText != null)
    <form hx-post="/c/${conversationId.toString()}/messages" hx-target="#messages" hx-swap="beforeend">
      <input type="hidden" name="text" value="${retryText}">
      <button type="submit" class="secondary">Retry</button>
    </form>
  @endif
</div>
