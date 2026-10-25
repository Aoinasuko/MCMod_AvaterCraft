"use strict";
(() => {
  const storageKey = "bep-3davater-guide-language";
  const parameters = new URLSearchParams(location.search);
  let stored;
  try { stored = localStorage.getItem(storageKey); } catch (_) { /* file:// may deny storage */ }
  const initial = parameters.get("lang") || stored || "ja";
  function setLanguage(language) {
    const lang = language === "en" ? "en" : "ja";
    document.documentElement.dataset.language = lang;
    document.documentElement.lang = lang;
    document.title = document.body.dataset[lang === "en" ? "titleEn" : "titleJa"] + " | B.E.P 3DAvater";
    document.querySelectorAll("[data-language-button]").forEach(button => {
      button.setAttribute("aria-pressed", String(button.dataset.languageButton === lang));
    });
    document.querySelectorAll("a[data-page-link]").forEach(link => {
      const target = new URL(link.getAttribute("href"), location.href);
      target.searchParams.set("lang", lang);
      link.href = target.href;
    });
    try { localStorage.setItem(storageKey, lang); } catch (_) { /* Query parameter keeps cross-page language. */ }
    try {
      const url = new URL(location.href);
      url.searchParams.set("lang", lang);
      history.replaceState(null, "", url);
    } catch (_) { /* Some local file viewers disallow history changes. */ }
  }
  document.querySelectorAll("[data-language-button]").forEach(button => {
    button.addEventListener("click", () => setLanguage(button.dataset.languageButton));
  });
  setLanguage(initial);
})();
