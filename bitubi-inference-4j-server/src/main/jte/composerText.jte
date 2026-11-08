@param boolean disabled
@param boolean oob
@param String placeholder
<textarea id="text" name="text" rows="1" required hx-swap-oob="${oob ? "true" : null}" aria-label="Message" placeholder="${placeholder}" disabled="${disabled}"></textarea>
