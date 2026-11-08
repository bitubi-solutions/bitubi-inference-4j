@import ao.bitubi.inference4j.server.ui.ViewModels.PageModel
@param String heading
@param String message
@template.layout(title = heading + " - bi4j", body = @`
<main class="error-page">
  <h1>${heading}</h1>
  <p>${message}</p>
  <p><a href="/">New chat</a></p>
</main>
`)
