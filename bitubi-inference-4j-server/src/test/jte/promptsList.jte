@import java.util.List
@param List<String> prompts
<ul id="prompts">
  @for(var prompt : prompts)
    <li>${prompt}</li>
  @endfor
</ul>
