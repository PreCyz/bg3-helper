"use strict";
document.querySelectorAll("[data-synergies]").forEach((details) => {
  details.addEventListener("toggle", async () => {
    const link = details.querySelector("[data-load-synergies]");
    if (!details.open || !link || details.getAttribute("aria-busy") === "true") return;
    details.setAttribute("aria-busy", "true");
    link.textContent = link.dataset.loading;
    try {
      const response = await fetch(link.href);
      if (!response.ok) throw new Error("Recommendations unavailable");
      const page = new DOMParser().parseFromString(await response.text(), "text/html");
      const card = page.getElementById(details.closest("article").id);
      const result = card?.querySelector(`[data-synergies="${details.dataset.synergies}"][open] [data-synergy-content]`);
      if (!result) throw new Error("Missing recommendations");
      details.querySelector("[data-synergy-content]").replaceWith(result);
    } catch {
      link.textContent = link.dataset.retry;
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
  const pointBuy = document.getElementById("point-buy");
  if (pointBuy) {
    const two = form.elements.plusTwo;
    const one = form.elements.plusOne;
    let previousTwo = two.value;
    let previousOne = one.value;
    pointBuy.addEventListener("input", (event) => {
      if (two.value === one.value) {
        if (event.target === two) one.value = previousTwo;
        if (event.target === one) two.value = previousOne;
      }
      previousTwo = two.value;
      previousOne = one.value;
      let spent = 0;
      let total = 0;
      const outputs = pointBuy.querySelectorAll("[data-final-score]");
      pointBuy.querySelectorAll('[name="purchased"]').forEach((select, i) => {
        spent += Number(select.selectedOptions[0].dataset.cost);
        const bonus = i === Number(two.value) ? 2 : i === Number(one.value) ? 1 : 0;
        const score = Number(select.value) + bonus;
        outputs[i].value = score;
        total += score;
      });
      document.getElementById("points-spent").value = spent;
      document.getElementById("score-total").value = total;
    });
  }
  function updateClassBudget() {
    const levels = [...form.querySelectorAll("[data-class-level]")];
    const total = levels.reduce((sum, input) => sum + (input.valueAsNumber || 0), 0);
    document.getElementById("total-level").value = total;
    for (const input of levels)
      input.setCustomValidity(total > 12 ? form.dataset.levelLimit : "");
    const picker = form.elements.newClass;
    if (picker) {
      const taken = new Set([form.elements.heroClass.value,
        ...[...form.querySelectorAll('[name="extraClass"]')].map(input => input.value)]);
      for (const option of picker.options) option.disabled = taken.has(option.value);
      if (taken.has(picker.value)) picker.value = "";
      form.querySelector('[value="addClass"]').disabled = !picker.value || total >= 12;
    }
  }
  updateClassBudget();
  form.addEventListener("input", updateClassBudget);
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
    if (event.target.name === "name" || event.target.name === "newClass") return;
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
        const focus = before.contains(document.activeElement) ? document.activeElement : null;
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
        if (focus?.name) {
          const inputs = [...after.querySelectorAll("select, input")].filter(input => input.name === focus.name);
          const index = [...before.querySelectorAll("select, input")].filter(input => input.name === focus.name).indexOf(focus);
          const replacement = focus.type === "checkbox" ? inputs.find(input => input.value === focus.value) : inputs[index];
          replacement?.focus({ preventScroll: true });
        }
        status.textContent = form.dataset.updated;
      } catch (error) {
        if (error.name !== "AbortError") {
          status.classList.remove("visually-hidden");
          status.textContent = form.dataset.retry;
        }
      }
    }, 220);
  }
  form.addEventListener("input", schedule);
}
