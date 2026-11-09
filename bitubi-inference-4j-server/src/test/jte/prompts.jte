@import java.util.List
@param List<String> prompts
@template.layout(title = "Prompts - bi4j", body = @`
<main>
  <h1>Prompts</h1>
  <div id="prompt-list" hx-get="/prompts" hx-trigger="load once" hx-swap="innerHTML">
    @template.promptsList(prompts = prompts)
  </div>
</main>
`)
