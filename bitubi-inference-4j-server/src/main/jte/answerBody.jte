@param String html
@param String footer
<div class="md">$unsafe{html}</div>@if(footer != null && !footer.isBlank())<p class="footer">${footer}</p>@endif
