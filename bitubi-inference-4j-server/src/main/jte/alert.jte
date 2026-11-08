@param String message
@param String linkHref
@param String linkText
<div id="alerts" role="alert" class="alert" hx-swap-oob="true"><p>${message}</p>@if(linkHref != null)<a href="${linkHref}">${linkText}</a>@endif</div>
