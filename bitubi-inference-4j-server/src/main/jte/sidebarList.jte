@import java.util.List
@import ao.bitubi.inference4j.server.ui.ViewModels.SidebarGroup
@param List<SidebarGroup> groups
@param String query
@if(groups.isEmpty())
  @if(query != null && !query.isBlank())
    <p class="empty">No conversations match. <a href="/">Clear the search</a></p>
  @else
    <p class="empty">No conversations yet.</p>
  @endif
@endif
@for(var group : groups)
  <section class="group">
    <h2 class="group-label">${group.label()}</h2>
    <ul>
      @for(var item : group.items())
        @template.sidebarItem(item = item)
      @endfor
    </ul>
  </section>
@endfor
