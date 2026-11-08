@import ao.bitubi.inference4j.server.ui.ViewModels.ModelStatus
@param ModelStatus model
<section id="model-status" class="status" role="status" hx-get="${model.failed() ? null : "/model/status"}" hx-trigger="${model.failed() ? null : "every 1s"}" hx-target="${model.failed() ? null : "#content"}" hx-swap="${model.failed() ? null : "innerHTML"}">
  @if(model.failed())
    <h2>The model could not be loaded</h2>
    <pre class="error-detail">${model.detail()}</pre>
    <p>Fix the cause above and start the server again. The command that was used is shown in the terminal.</p>
  @elseif(model.downloading())
    <h2>Downloading the model</h2>
    <p>${model.detail()}</p>
    <progress value="${model.done()}" max="${model.total()}"></progress>
  @else
    <h2>Loading the model</h2>
    <p>${model.detail()}</p>
  @endif
  <p class="hint">Stored conversations can be read from the sidebar meanwhile.</p>
</section>
