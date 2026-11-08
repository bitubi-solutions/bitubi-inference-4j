@import java.util.UUID
@import ao.bitubi.inference4j.server.ui.ViewModels.MessageModel
@param MessageModel msg
@param UUID conversationId
@if(msg.user())
  <li class="msg user"><div class="who">You</div><div class="body user-text">${msg.text()}</div>
    @if(msg.interrupted())
      @template.interrupted(conversationId = conversationId, retryText = msg.retryText())
    @endif
  </li>
@else
  <li class="msg assistant"><div class="who">bi4j</div>
    <div class="body answer">@template.answerBody(html = msg.html(), footer = msg.footer())</div>
    @if(msg.interrupted())
      @template.interrupted(conversationId = conversationId, retryText = msg.retryText())
    @endif
  </li>
@endif
