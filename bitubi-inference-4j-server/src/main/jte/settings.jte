@import java.util.UUID
@param UUID conversationId
@param String temperature
@param int maxTokens
@param int maxContext
@param String system
@param String note
@param String error
<form id="settings-form" class="settings" hx-put="/c/${conversationId.toString()}/settings" hx-target="#settings-body" hx-swap="innerHTML">
  <h2 id="settings-title">Settings</h2>
  <label for="s-temperature">Temperature (0 to 2)</label>
  <input id="s-temperature" type="number" name="temperature" min="0" max="2" step="0.1" value="${temperature}" required>
  <label for="s-max">Max new tokens</label>
  <input id="s-max" type="number" name="maxTokens" min="1" max="${maxContext}" step="1" value="${maxTokens}" required>
  <label for="s-system">System prompt</label>
  <textarea id="s-system" name="system" rows="4">${system}</textarea>
  <div class="actions">
    <button type="submit" class="primary">Save</button>
    <button type="button" class="secondary" hx-delete="/c/${conversationId.toString()}/settings" hx-target="#settings-body" hx-swap="innerHTML">Reset</button>
  </div>
  @if(error != null)<p class="form-error" role="alert">${error}</p>@endif
  @if(note != null)<p id="settings-note" role="status">${note}</p>@endif
</form>
