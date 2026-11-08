@import ao.bitubi.inference4j.server.ui.ViewModels.PageModel
@param PageModel page
@template.layout(title = page.pageTitle(), body = @`
<div class="app">
  @template.sidebar(groups = page.sidebar(), query = page.query(), historyWritable = page.historyWritable(), historyError = page.historyError())
  <div class="main">
    <header class="top">
      <h1 class="model-name">${page.model().name()}</h1>
      <span id="model-info" class="model-info">${page.model().info()}</span>
    </header>
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
