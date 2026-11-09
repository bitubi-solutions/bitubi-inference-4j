@import ao.bitubi.inference4j.server.ui.ViewModels.PageModel
@param PageModel page
@template.layout(title = page.pageTitle(), body = @`
<div class="app" id="app">
  @template.sidebar(groups = page.sidebar(), query = page.query(), historyWritable = page.historyWritable(), historyError = page.historyError())
  <div id="backdrop" class="backdrop"></div>
  @template.shortcuts()
  <div class="main">
    <header class="top">
      <button type="button" id="sidebar-toggle" class="header-btn menu-btn" aria-expanded="false" aria-controls="sidebar">Menu</button>
      <h1 class="model-name">${page.model().name()}</h1>
      <span id="model-info" class="model-info">${page.model().info()}</span>
      <div class="top-actions">
      @if(page.chat() != null)
        <button type="button" id="settings-open" class="header-btn" aria-expanded="false" aria-controls="settings-panel" hx-get="/c/${page.chat().id().toString()}/settings" hx-target="#settings-body" hx-swap="innerHTML">Settings</button>
      @endif
        <button type="button" id="theme-toggle" class="header-btn">Theme</button>
      </div>
    </header>
    <aside id="settings-panel" class="drawer" aria-label="Settings" tabindex="-1">
      <button type="button" class="drawer-close" data-close-panel aria-label="Close settings">Close</button>
      <div id="settings-body"></div>
    </aside>
    <div id="content">
      @if(page.model().ready())
        @template.chat(chat = page.chat())
      @else
        @template.status(model = page.model())
      @endif
    </div>
  </div>
</div>
`)
