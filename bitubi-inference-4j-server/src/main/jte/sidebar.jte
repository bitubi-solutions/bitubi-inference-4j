@import java.util.List
@import ao.bitubi.inference4j.server.ui.ViewModels.SidebarGroup
@param List<SidebarGroup> groups
@param String query
@param boolean historyWritable
@param String historyError
<nav id="sidebar" aria-label="Conversations">
  <div class="brand"><img src="/logo.svg" alt="" width="24" height="24"><span>bi4j</span></div>
  <a class="new-chat" href="/">New chat</a>
  <form id="search" role="search" hx-get="/sidebar" hx-trigger="input changed delay:200ms, search" hx-target="#conversation-list" hx-swap="innerHTML">
    <input type="search" name="q" value="${query}" placeholder="Search conversations" aria-label="Search conversations" autocomplete="off">
  </form>
  @if(!historyWritable)
    <p class="warn" role="status">History is not being saved: ${historyError}</p>
  @endif
  <div id="conversation-list">
    @template.sidebarList(groups = groups, query = query)
  </div>
  <p class="sidebar-foot">Conversations are stored as plain text on this machine.</p>
</nav>
