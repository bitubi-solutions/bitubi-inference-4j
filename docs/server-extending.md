# Extending the chat UI

The UI is server-rendered: **a page is one template, one controller method and one test**. There is no client-side
framework and no build step. This guide adds a "Prompts" page; every code block below is the real file, checked by a test.

## The rules

1. **A template is a `.jte` file** in `bitubi-inference-4j-server/src/main/jte`. It takes typed parameters (`@param`) and
   escapes everything by default. Use `$unsafe{}` only for HTML you rendered yourself (the Markdown renderer does).
2. **A route returns HTML.** A browser asks for the **full page** (layout, sidebar); htmx asks for a **fragment** and says so
   with the `HX-Request` header. Return the page for plain requests and the fragment for htmx ones, and send
   `Vary: HX-Request` so caches do not mix them.
3. **Strict Content Security Policy.** No inline `<script>`, no `style="..."`, no `onclick`. Behaviour goes in `app.js`
   (delegated listeners), looks in `app.css`. Anything else is blocked by the browser and fails the tests.
4. **Accessibility is part of done**: a named control for every button, one `h1`, labels on inputs, 44 px targets, both themes.
   `A11yE2E` audits the built-in states with axe-core; add yours to it.
5. htmx attributes do the talking (`hx-get`, `hx-post`, `hx-target`, `hx-swap`). Swap out-of-band pieces
   (`hx-swap-oob="true"`) only in responses that are swapped into a *target*, never inside a fragment that is loaded as a page.

## The worked example: a Prompts page

### 1. The fragment

<!-- verbatim: bitubi-inference-4j-server/src/test/jte/promptsList.jte -->
```jte
@import java.util.List
@param List<String> prompts
<ul id="prompts">
  @for(var prompt : prompts)
    <li>${prompt}</li>
  @endfor
</ul>
```
### 2. The page

It uses the built-in layout and loads the fragment with htmx once the page is open:

<!-- verbatim: bitubi-inference-4j-server/src/test/jte/prompts.jte -->
```jte
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
```
### 3. The controller method

<!-- verbatim: bitubi-inference-4j-server/src/test/java/ao/bitubi/inference4j/server/prompts/PromptsController.java -->
```java
package ao.bitubi.inference4j.server.prompts;

import ao.bitubi.inference4j.server.ui.Templates;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/** The worked example of docs/server-extending.md: a "Prompts" page. */
@Controller
@ConditionalOnProperty("bi4j.test.prompts")
class PromptsController {
    private final Templates templates;
    private final List<String> prompts = List.of("Explain Java streams in simple terms", "Write a short poem about autumn");

    PromptsController(Templates templates) {
        this.templates = templates;
    }

    @GetMapping("/prompts")
    ResponseEntity<String> prompts(@RequestHeader(name = "HX-Request", required = false) String htmx) {
        String page = templates.render(htmx == null ? "prompts" : "promptsList", Map.of("prompts", prompts));
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).header("Vary", "HX-Request").body(page);
    }
}
```
`Templates.render(name, params)` renders `name.jte` with a map of parameters. Put your controller under
`ao.bitubi.inference4j.server` (it is found by component scan); the `@ConditionalOnProperty` above exists only so this example
does not load in every test, drop it in your own.

### 4. The test

`PromptsGuideTest` (same folder) boots the real server, asks `/prompts` as a browser and as htmx, and checks the layout is
there in the first case and absent in the second. It points `bi4j.ui.template-dir` at a copy of the templates, which is how
a template that is not part of the build is picked up.

## The dev loop, without a model

```shell
./dev-server.sh            # about 9 seconds to the printed URL; the first page adds about 2 seconds while templates compile
```

It runs `DevServer`: the real application on a scripted fake model (`--delay-ms`, `--fail-at` to see slow and failing
answers), with `bi4j.ui.template-dir` and the static folder pointing at the source tree, so **edit a `.jte`, `app.css` or
`app.js`, save, refresh**. No download, no model, no JVM flag for the vector module. The first run builds what it needs.

## Extension points

- **`ConversationStore`** (package `ao.bitubi.inference4j.server.chat`): where conversations live. The default writes plain-text
  files; another implementation can replace the bean. Use `update(id, change)` for read-modify-write so concurrent changes of one
  conversation are not lost. *Unstable until a second implementation exists.*
- **Servlet filters**: the Host and Origin checks are ordinary filters; authentication belongs in front of them.
- Log lines and the `/healthz` endpoint are the observability surface; the SDK emits the generation events.
