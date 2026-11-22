"use strict";
document.querySelectorAll("[data-synergies]").forEach((details) => {
  details.addEventListener("toggle", async () => {
    const link = details.querySelector("[data-load-synergies]");
    if (!details.open || !link || details.getAttribute("aria-busy") === "true") return;
    details.setAttribute("aria-busy", "true");
    link.textContent = "Loading matching equipment…";
    try {
      const response = await fetch(link.href);
      if (!response.ok) throw new Error("Recommendations unavailable");
      const page = new DOMParser().parseFromString(await response.text(), "text/html");
      const card = page.getElementById(details.closest("article").id);
      const result = card?.querySelector("[data-synergies][open] [data-synergy-content]");
      if (!result) throw new Error("Missing recommendations");
      details.querySelector("[data-synergy-content]").replaceWith(result);
    } catch {
      link.textContent = "Open matching equipment";
    } finally {
      details.removeAttribute("aria-busy");
    }
  });
});
document
  .querySelectorAll("[data-submit]")
  .forEach((select) =>
    select.addEventListener("change", () => select.form.requestSubmit()),
  );
const form = document.getElementById("wizardForm");
if (form) {
  let timer;
  let sequence = 0;
  let pending;
  const status = document.getElementById("preview-status");
  form.addEventListener("submit", () => {
    clearTimeout(timer);
    sequence++;
    pending?.abort();
  });
  function schedule(event) {
    if (event.target.name === "name") return;
    clearTimeout(timer);
    pending?.abort();
    const revision = ++sequence;
    if (!form.checkValidity()) return;
    timer = setTimeout(async () => {
      pending = new AbortController();
      const body = new URLSearchParams(new FormData(form));
      body.set("action", "preview");
      try {
        const response = await fetch(form.getAttribute("action"), {
          method: "POST",
          body,
          signal: pending.signal,
        });
        if (!response.ok) throw new Error("Preview unavailable");
        const page = new DOMParser().parseFromString(
          await response.text(),
          "text/html",
        );
        if (revision !== sequence) return;
        const before = document.getElementById("development");
        const selects = [...before.querySelectorAll("select")];
        const focusIndex = selects.indexOf(document.activeElement);
        const after = page.getElementById("development");
        const preview = page.getElementById("preview");
        if (!after || !preview) throw new Error("Incomplete preview");
        const expanded = [...document.querySelectorAll("#preview details")].map(
          (d) => d.open,
        );
        preview
          .querySelectorAll("details")
          .forEach((d, i) => (d.open = expanded[i] ?? d.open));
        before.replaceWith(after);
        document.getElementById("preview").replaceWith(preview);
        if (focusIndex >= 0)
          after
            .querySelectorAll("select")
            [focusIndex]?.focus({ preventScroll: true });
        status.textContent = "Stat preview updated.";
      } catch (error) {
        if (error.name !== "AbortError") {
          status.classList.remove("visually-hidden");
          status.textContent = "Use Update preview to refresh the statistics.";
        }
      }
    }, 220);
  }
  form.addEventListener("input", schedule);
}
