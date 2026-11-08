@import ao.bitubi.inference4j.server.ui.ViewModels.SidebarItem
@param SidebarItem item
<li class="conv renaming" id="conv-${item.id().toString()}">
  <form hx-patch="/c/${item.id().toString()}" hx-target="closest li" hx-swap="outerHTML">
    <input type="text" name="title" value="${item.title()}" maxlength="120" required aria-label="New title" data-rename-input data-cancel-url="/c/${item.id().toString()}/item">
    <button type="submit" class="icon">Save</button>
  </form>
</li>
