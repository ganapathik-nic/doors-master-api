(function () {
  "use strict";

  function escapeHtml(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function renderContract(contract) {
    var statusClass = contract.contractStatus === "APPROVED" ? "validated" : "undiscovered";
    return [
      '<details class="doors-contract">',
      '<summary><code>', escapeHtml(contract.uniqueName), '</code>',
      '<span class="doors-contract-status ', statusClass, '">',
      escapeHtml(contract.contractStatus), '</span>',
      '<span>', escapeHtml(contract.description || ""), '</span></summary>',
      '<div class="doors-contract-grid">',
      '<section><h4>Request contract</h4><pre>',
      escapeHtml(JSON.stringify(contract.requestSchema, null, 2)),
      '</pre></section>',
      '<section><h4>Decrypted response contract</h4><pre>',
      escapeHtml(contract.responseSchema
        ? JSON.stringify(contract.responseSchema, null, 2)
        : "No reviewed and approved response contract has been published yet."),
      '</pre></section>',
      '</div>',
      contract.contractStatus === "APPROVED"
        ? '<button type="button" class="doors-contract-export" data-contract="' +
          escapeHtml(contract.uniqueName) + '">Export Contract JSON</button>'
        : '',
      '</details>'
    ].join("");
  }

  async function exportContract(uniqueName, button) {
    var originalText = button.textContent;
    button.disabled = true;
    button.textContent = "Preparing export...";
    try {
      var response = await fetch("api/v1/master/gateway/swagger-sessions/contract/export", { headers: {
          Accept: "application/json",
          "X-DOORS-SWAGGER-SESSION": window.doorsSwaggerSessionToken || ""
        } });
      if (!response.ok) throw new Error("HTTP " + response.status);
      var blob = await response.blob();
      var link = document.createElement("a");
      link.href = URL.createObjectURL(blob);
      link.download = uniqueName.replace(/[^A-Za-z0-9._-]/g, "_") + "-contract.json";
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(link.href);
    } catch (error) {
      window.alert("Unable to export approved contract: " + (error.message || error));
    } finally {
      button.disabled = false;
      button.textContent = originalText;
    }
  }

  async function loadCatalogue() {
    var host = document.getElementById("doors-template-catalogue");
    if (!host) return;
    try {
      var response = await fetch("api/v1/master/gateway/swagger-sessions/contract", {
        headers: {
          Accept: "application/json",
          "X-DOORS-SWAGGER-SESSION": window.doorsSwaggerSessionToken || ""
        }
      });
      if (!response.ok) throw new Error("HTTP " + response.status);
      var body = await response.json();
      var contracts = body.payload ? [body.payload] : [];
      var selected = (window.doorsSwaggerLaunchConfig || {}).uniqueName;
      host.innerHTML = contracts.length
        ? contracts.map(renderContract).join("")
        : '<p class="doors-catalogue-empty">No approved template contracts are published.</p>';
      if (selected) {
        var first = host.querySelector("details");
        if (first) first.open = true;
      }
    } catch (error) {
      host.innerHTML = '<p class="doors-catalogue-error">Unable to load Template Catalogue: ' +
        escapeHtml(error.message || error) + '</p>';
    }
  }

  window.addEventListener("doors-swagger-ready", function () {
    loadCatalogue();
    var host = document.getElementById("doors-template-catalogue");
    if (host) host.addEventListener("click", function (event) {
      var button = event.target.closest(".doors-contract-export");
      if (button) exportContract(button.getAttribute("data-contract"), button);
    });
  });
})();
