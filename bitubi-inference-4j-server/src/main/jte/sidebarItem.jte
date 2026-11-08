@import ao.bitubi.inference4j.server.ui.ViewModels.SidebarItem
@param SidebarItem item
<li class="conv" id="conv-${item.id().toString()}">
  <a href="/c/${item.id().toString()}" aria-current="${item.current() ? "page" : null}">${item.title().isBlank() ? "New chat" : item.title()}</a>
  <button type="button" class="icon" aria-label="Rename conversation" hx-get="/c/${item.id().toString()}/rename" hx-target="closest li" hx-swap="outerHTML">Rename</button>
  <button type="button" class="icon" aria-label="Delete conversation" data-open-dialog>Delete</button>
  <dialog class="confirm" aria-label="Delete conversation">
    <form method="dialog" class="confirm-form">
      <p>Delete this conversation? It cannot be undone.</p>
      <button value="cancel" class="secondary">Cancel</button>
      <button type="button" class="danger" hx-delete="/c/${item.id().toString()}" hx-target="#conversation-list" hx-swap="innerHTML">Delete</button>
    </form>
  </dialog>
</li>
