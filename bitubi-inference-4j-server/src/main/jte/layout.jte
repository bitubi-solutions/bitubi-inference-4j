@import gg.jte.Content
@param String title
@param Content body
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title}</title>
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<link rel="stylesheet" href="/app.css">
<script src="/webjars/htmx.org/4.0.1/dist/htmax.min.js" defer></script>
<script src="/app.js" defer></script>
</head>
<body>
${body}
<noscript><p class="noscript">This page needs JavaScript (htmx) to send messages. Stored conversations can still be read.</p></noscript>
</body>
</html>
