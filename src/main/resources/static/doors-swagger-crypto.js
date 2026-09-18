(function () {
  "use strict";

  var privateKey = null;
  var lastEncryptedEnvelope = null;
  var responseDecryptAction = null;
  var decryptedOutput = null;
  var swaggerObserver = null;

  function isPlainDocumentService() {
    var launchConfig = window.doorsSwaggerLaunchConfig || {};
    return Boolean(launchConfig.operationPath) && launchConfig.payloadMode !== "AES";
  }

  function setDecryptedActionsEnabled(enabled) {
    var copyButton = document.getElementById("doors-copy-decrypted-button");
    var downloadButton = document.getElementById("doors-download-decrypted-button");
    if (copyButton) copyButton.disabled = !enabled;
    if (downloadButton) downloadButton.disabled = !enabled;
  }

  function decryptedText() {
    var output = document.getElementById("doors-response-decrypted-output") ||
      document.getElementById("doors-decrypted-output") || decryptedOutput;
    return output && !output.hidden ? output.textContent : "";
  }

  async function copyDecryptedData() {
    var text = decryptedText();
    if (!text) return;
    await navigator.clipboard.writeText(text);
    setStatus("Decrypted JSON copied to clipboard", "ready");
  }

  function downloadDecryptedData() {
    var text = decryptedText();
    if (!text) return;
    var blob = new Blob([text], { type: "application/json;charset=utf-8" });
    var link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = "doors-decrypted-response-" + Date.now() + ".json";
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(link.href);
    setStatus("Decrypted JSON downloaded", "ready");
  }

  function setStatus(message, state) {
    var status = document.getElementById("doors-crypto-status");
    if (!status) return;
    status.textContent = message;
    status.className = "doors-crypto-status" + (state ? " " + state : "");
  }

  function updateDecryptButton() {
    var button = document.getElementById("doors-decrypt-button");
    if (button) button.disabled = !(privateKey && lastEncryptedEnvelope);
    var responseButton = document.getElementById("doors-response-decrypt-button");
    if (responseButton) responseButton.disabled = !lastEncryptedEnvelope;
    var requestButton = document.getElementById("doors-build-request-button");
    if (requestButton) requestButton.disabled = !privateKey;
  }

  function arrayBufferToBinary(buffer) {
    var bytes = new Uint8Array(buffer);
    var result = "";
    var chunkSize = 0x8000;
    for (var offset = 0; offset < bytes.length; offset += chunkSize) {
      result += String.fromCharCode.apply(
        null,
        bytes.subarray(offset, Math.min(offset + chunkSize, bytes.length))
      );
    }
    return result;
  }

  function findPrivateKey(pkcs12) {
    var shrouded = pkcs12.getBags({
      bagType: forge.pki.oids.pkcs8ShroudedKeyBag
    })[forge.pki.oids.pkcs8ShroudedKeyBag] || [];
    var plain = pkcs12.getBags({
      bagType: forge.pki.oids.keyBag
    })[forge.pki.oids.keyBag] || [];
    var bags = shrouded.concat(plain);
    for (var index = 0; index < bags.length; index++) {
      if (bags[index].key) return bags[index].key;
    }
    throw new Error("No RSA private key was found in this PKCS#12 keystore.");
  }

  async function fingerprintPrivateKey(key) {
    var publicKey = forge.pki.setRsaPublicKey(key.n, key.e);
    // Java PublicKey#getEncoded() returns X.509 SubjectPublicKeyInfo (SPKI).
    // Hash the same canonical structure here; hashing the inner PKCS#1
    // RSAPublicKey produces a different fingerprint for the very same key.
    var publicKeyInfo = forge.pki.publicKeyToSubjectPublicKeyInfo(publicKey);
    var der = forge.asn1.toDer(publicKeyInfo).getBytes();
    var bytes = new Uint8Array(der.length);
    for (var index = 0; index < der.length; index++) bytes[index] = der.charCodeAt(index);
    var digest = await crypto.subtle.digest("SHA-256", bytes);
    return Array.from(new Uint8Array(digest))
      .map(function (value) { return value.toString(16).padStart(2, "0"); })
      .join(":")
      .toUpperCase();
  }

  async function loadPrivateKey() {
    preparedRequest = null;
    var fileInput = document.getElementById("doors-p12-file");
    var passwordInput = document.getElementById("doors-p12-password");
    var file = fileInput && fileInput.files ? fileInput.files[0] : null;
    privateKey = null;
    updateDecryptButton();

    if (!file) {
      setStatus("Select the client .p12 file");
      return;
    }
    if (!passwordInput || !passwordInput.value) {
      setStatus("Enter the PKCS#12 keystore password");
      return;
    }

    try {
      setStatus("Opening keystore...");
      var binary = arrayBufferToBinary(await file.arrayBuffer());
      var asn1 = forge.asn1.fromDer(binary);
      var pkcs12 = forge.pkcs12.pkcs12FromAsn1(asn1, false, passwordInput.value);
      var loadedPrivateKey = findPrivateKey(pkcs12);
      var launchConfig = window.doorsSwaggerLaunchConfig || {};
      var expectedFingerprint = launchConfig.keyFingerprint;
      var keySource = launchConfig.keySource || "DATABASE";
      var actualFingerprint = await fingerprintPrivateKey(loadedPrivateKey);

      if (expectedFingerprint &&
          actualFingerprint.replace(/:/g, "") !== expectedFingerprint.replace(/:/g, "").toUpperCase()) {
        throw new Error(
          "KEY MISMATCH: this .p12 fingerprint is " + actualFingerprint +
          ", but the effective " + keySource + " key is " + expectedFingerprint + "."
        );
      }

      privateKey = loadedPrivateKey;
      updateDecryptButton();
      setStatus("Private key matches - click Decrypt Response", "ready");
    } catch (error) {
      privateKey = null;
      updateDecryptButton();
      setStatus("Unable to open keystore: " + (error.message || error), "error");
    }
  }

  function parseEnvelope(response) {
    if (response && response.body && typeof response.body === "object") return response.body;
    if (response && typeof response.text === "string" && response.text.trim()) {
      return JSON.parse(response.text);
    }
    return null;
  }

  function placeCryptoPanelBelowExecute() {
    var panel = document.getElementById("doors-crypto-panel");
    if (!panel) return;
    var wrappers = document.querySelectorAll(".execute-wrapper");
    var wrapper = wrappers.length ? wrappers[wrappers.length - 1] : null;
    if (isPlainDocumentService()) {
      panel.hidden = true;
      if (wrapper) {
        var plainExecuteButton = wrapper.querySelector("button.execute");
        if (plainExecuteButton) plainExecuteButton.textContent = "Fetch Data";
      }
      return;
    }
    if (wrapper) {
      panel.hidden = false;
      if (panel.previousElementSibling !== wrapper) {
        wrapper.insertAdjacentElement("afterend", panel);
      }
      var executeButton = wrapper.querySelector("button.execute");
      if (executeButton && executeButton.textContent !== "Fetch Data") {
        executeButton.textContent = "Fetch Data";
      }
    }
  }

  function placeRequestWrapperBeforeResponse(output) {
    var wrappers = document.querySelectorAll(".responses-wrapper");
    var wrapper = wrappers.length ? wrappers[wrappers.length - 1] : null;
    if (wrapper && output) {
      wrapper.insertAdjacentElement("beforebegin", output);
    }
  }

  function placeFetchButtonBelowRequestWrapper(output) {
    var buttons = document.querySelectorAll("button.execute");
    var button = buttons.length ? buttons[buttons.length - 1] : null;
    if (button && output) {
      button.textContent = "Fetch Data";
      button.style.display = "block";
      button.style.margin = "12px 20px 16px";
      output.insertAdjacentElement("afterend", button);
    }
  }

  function placeDecryptedOutputAfterResponse(output) {
    var decryptAction =
      document.getElementById("doors-response-decrypt-action") ||
      responseDecryptAction;
    if (decryptAction && output) {
      decryptAction.appendChild(output);
      return;
    }
    var wrappers = document.querySelectorAll(".responses-wrapper");
    var wrapper = wrappers.length ? wrappers[wrappers.length - 1] : null;
    if (wrapper && output) {
      wrapper.insertAdjacentElement("afterend", output);
    }
  }

  function placeDecryptButtonBelowResponseHeaders() {
    if (isPlainDocumentService()) return;
    var wrappers = document.querySelectorAll(".responses-wrapper");
    var wrapper = wrappers.length ? wrappers[wrappers.length - 1] : null;
    if (!wrapper || !lastEncryptedEnvelope) return;

    var headings = wrapper.querySelectorAll("h4, h5, h6, .response-control-media-type__title");
    var responseHeadersHeading = null;
    for (var index = 0; index < headings.length; index++) {
      if (headings[index].textContent.trim().toLowerCase() === "response headers") {
        responseHeadersHeading = headings[index];
        break;
      }
    }
    if (!responseHeadersHeading) return;

    var action =
      document.getElementById("doors-response-decrypt-action") ||
      responseDecryptAction;
    if (!action) {
      action = document.createElement("div");
      action.id = "doors-response-decrypt-action";
      action.style.margin = "12px 0 16px";

      var button = document.createElement("button");
      button.id = "doors-response-decrypt-button";
      button.className = "doors-decrypt-button";
      button.type = "button";
      button.textContent = "Decrypt Data";
      button.addEventListener("click", decryptLastResponse);
      action.appendChild(button);

      var copyButton = document.createElement("button");
      copyButton.id = "doors-copy-decrypted-button";
      copyButton.className = "doors-decrypt-button doors-decrypted-action-button";
      copyButton.type = "button";
      copyButton.textContent = "Copy";
      copyButton.disabled = true;
      copyButton.addEventListener("click", copyDecryptedData);
      action.appendChild(copyButton);

      var downloadButton = document.createElement("button");
      downloadButton.id = "doors-download-decrypted-button";
      downloadButton.className = "doors-decrypt-button doors-decrypted-action-button";
      downloadButton.type = "button";
      downloadButton.textContent = "Download JSON";
      downloadButton.disabled = true;
      downloadButton.addEventListener("click", downloadDecryptedData);
      action.appendChild(downloadButton);

      var inlineOutput = document.createElement("pre");
      inlineOutput.id = "doors-response-decrypted-output";
      inlineOutput.className = "doors-decrypted-output";
      inlineOutput.style.marginTop = "12px";
      inlineOutput.hidden = true;
      action.appendChild(inlineOutput);
      responseDecryptAction = action;
    }

    var headersBlock = responseHeadersHeading.nextElementSibling || responseHeadersHeading;
    if (headersBlock.nextElementSibling !== action) {
      headersBlock.insertAdjacentElement("afterend", action);
    }
    updateDecryptButton();
  }

  function decryptLastResponse() {
    var output =
      document.getElementById("doors-response-decrypted-output") ||
      document.getElementById("doors-decrypted-output") ||
      decryptedOutput;

    if (!lastEncryptedEnvelope || !privateKey) {
      var missingMessage = !lastEncryptedEnvelope
        ? "Fetch encrypted data before decrypting."
        : "Load the matching .p12 file and enter its password before decrypting.";
      if (output) {
        output.textContent = missingMessage;
        output.hidden = false;
      }
      setStatus(missingMessage, "error");
      return;
    }

    try {
      var aesKey = privateKey.decrypt(
        forge.util.decode64(lastEncryptedEnvelope.encryptedKey),
        "RSAES-PKCS1-V1_5"
      );
      var decipher = forge.cipher.createDecipher("AES-CBC", aesKey);
      decipher.start({ iv: forge.util.decode64(lastEncryptedEnvelope.iv) });
      decipher.update(
        forge.util.createBuffer(forge.util.decode64(lastEncryptedEnvelope.secureData))
      );
      if (!decipher.finish()) throw new Error("AES-CBC padding validation failed.");

      var plaintext = forge.util.decodeUtf8(decipher.output.getBytes());
      output.textContent = JSON.stringify(JSON.parse(plaintext), null, 2);
      output.hidden = false;
      setDecryptedActionsEnabled(true);
      setStatus("Encrypted response decrypted successfully", "ready");
    } catch (error) {
      var failureMessage = "Response decryption failed: " + (error.message || error);
      if (output) {
        output.textContent = failureMessage;
        output.hidden = false;
      }
      setDecryptedActionsEnabled(false);
      setStatus(failureMessage, "error");
    }
  }

  function decodeBase64Url(value) {
    var normalized = value.replace(/-/g, "+").replace(/_/g, "/");
    while (normalized.length % 4) normalized += "=";
    return forge.util.decode64(normalized);
  }

  async function fetchMasterPublicKey() {
    var response = await fetch("api/v1/master/gateway/.well-known/jwks.json", {
      credentials: "omit",
      headers: { "Accept": "application/json" }
    });
    if (!response.ok) {
      throw new Error("Unable to load Master API JWKS (HTTP " + response.status + ").");
    }
    var jwks = await response.json();
    var jwk = jwks && jwks.keys && jwks.keys[0];
    if (!jwk || !jwk.n || !jwk.e) {
      throw new Error("Master API JWKS does not contain an RSA public key.");
    }
    var modulus = new forge.jsbn.BigInteger(
      forge.util.bytesToHex(decodeBase64Url(jwk.n)),
      16
    );
    var exponent = new forge.jsbn.BigInteger(
      forge.util.bytesToHex(decodeBase64Url(jwk.e)),
      16
    );
    return forge.pki.setRsaPublicKey(modulus, exponent);
  }

  var preparedRequest = null;
  window.doorsPrepareRequest = function (request) {
    var config = window.doorsSwaggerLaunchConfig || {};
    var path = new URL(request.url, window.location.href).pathname;
    var expected = '/api/v1/master/gateway/orchestrate/' + encodeURIComponent(config.uniqueName || '');
    if (String(request.method).toUpperCase() !== 'POST' || path !== expected) return request;
    var current;
    try { current = JSON.stringify(typeof request.body === 'string' ? JSON.parse(request.body) : request.body); }
    catch (_) { throw new Error('Invalid request JSON. Correct the parameters and rebuild the encrypted request.'); }
    if (!preparedRequest || !privateKey || preparedRequest.source !== current) {
      setStatus('Build the encrypted request after loading your key or changing parameters.', 'error');
      throw new Error('Build the encrypted request after loading your key or changing parameters.');
    }
    request.body = preparedRequest.wire;
    return request;
  };

  async function buildEncryptedRequestWrapper() {
    preparedRequest = null;
    if (!privateKey) {
      setStatus("Load the matching .p12 and password first", "error");
      return;
    }

    try {
      setStatus("Building encrypted request wrapper...");
      var bodyEditor = document.querySelector("textarea.body-param__text") ||
        document.querySelector(".body-param__text");
      var launchConfig = window.doorsSwaggerLaunchConfig || {};
      var rawBody = bodyEditor && bodyEditor.value
        ? bodyEditor.value
        : launchConfig.body ? JSON.stringify(launchConfig.body) : null;
      if (!rawBody) throw new Error("No populated Swagger request body was found.");

      // Validate while preserving the exact plaintext bytes used for encryption/signing.
      var parsedBody = JSON.parse(rawBody);
      var sourceBody = JSON.stringify(parsedBody);
      var isDocument = String(launchConfig.operationPath || '').includes('/documents/');
      if (!isDocument) {
        delete parsedBody.protocolVersion;
        if (launchConfig.protocolVersion === 2) {
          parsedBody.protocolVersion = 2;
          parsedBody.clientId = launchConfig.clientId;
          parsedBody.query = launchConfig.uniqueName;
          parsedBody.requestId = crypto.randomUUID();
          parsedBody.issuedAt = new Date().toISOString();
        }
        rawBody = JSON.stringify(parsedBody);
      }
      var masterPublicKey = await fetchMasterPublicKey();
      var dynamicAesKey = forge.random.getBytesSync(32);
      var requestAesKey = isDocument ? dynamicAesKey.substring(0, 16) : dynamicAesKey;

      var requestIv = forge.random.getBytesSync(12);
      var aesCipher = forge.cipher.createCipher(isDocument ? "AES-ECB" : "AES-GCM", requestAesKey);
      aesCipher.start(isDocument ? {} : {iv: requestIv, tagLength: 128});
      aesCipher.update(forge.util.createBuffer(forge.util.encodeUtf8(rawBody)));
      if (!aesCipher.finish()) throw new Error("Unable to encrypt the request payload.");

      var digest = forge.md.sha512.create();
      digest.update(rawBody, "utf8");

      var wrapper = {
        encryptedKey: forge.util.encode64(
          masterPublicKey.encrypt(dynamicAesKey, "RSAES-PKCS1-V1_5")
        ),
        secureData: forge.util.encode64(aesCipher.output.getBytes() + (isDocument ? '' : aesCipher.mode.tag.getBytes())),
        signature: forge.util.encode64(privateKey.sign(digest))
      };

      if (!isDocument) {
        wrapper.iv = forge.util.encode64(requestIv);
        if (launchConfig.protocolVersion === 2) wrapper.protocolVersion = 2;
        preparedRequest = { source: sourceBody, wire: JSON.stringify(wrapper) };
      }

      var output = document.getElementById("doors-encrypted-request-output");
      output.textContent =
        "ENCRYPTED REQUEST JSON WRAPPER\n\n" + JSON.stringify(wrapper, null, 2);
      output.hidden = false;
      placeRequestWrapperBeforeResponse(output);
      placeFetchButtonBelowRequestWrapper(output);
      setStatus("Encrypted request ready. Fetch Data will send this wrapper, not the plain parameters.", "ready");
    } catch (error) {
      setStatus("Request wrapper generation failed: " + (error.message || error), "error");
    }
  }

  window.doorsDecryptResponse = function (response) {
    var envelope;
    try {
      envelope = parseEnvelope(response);
    } catch (ignored) {
      return response;
    }
    if (!envelope || !envelope.encryptedKey || !envelope.iv || !envelope.secureData) {
      return response;
    }

    lastEncryptedEnvelope = envelope;
    setDecryptedActionsEnabled(false);
    var previousDecryptedOutput = document.getElementById("doors-decrypted-output") || decryptedOutput;
    if (previousDecryptedOutput) {
      previousDecryptedOutput.textContent = "";
      previousDecryptedOutput.hidden = true;
    }
    var previousInlineOutput = document.getElementById("doors-response-decrypted-output");
    if (previousInlineOutput) {
      previousInlineOutput.textContent = "";
      previousInlineOutput.hidden = true;
    }
    updateDecryptButton();
    setStatus(
      privateKey
        ? "Encrypted wrapper received - click Decrypt Response"
        : "Encrypted wrapper received - load the matching .p12 and password"
    );
    setTimeout(placeCryptoPanelBelowExecute, 100);
    setTimeout(placeDecryptButtonBelowResponseHeaders, 100);
    setTimeout(placeDecryptButtonBelowResponseHeaders, 350);

    // Force Swagger's standard response display to retain the encrypted wire payload.
    response.body = envelope;
    response.text = JSON.stringify(envelope, null, 2);
    return response;
  };

  window.addEventListener("DOMContentLoaded", function () {
    var fileInput = document.getElementById("doors-p12-file");
    var passwordInput = document.getElementById("doors-p12-password");
    var decryptButton = document.getElementById("doors-decrypt-button");
    var requestButton = document.getElementById("doors-build-request-button");
    decryptedOutput = document.getElementById("doors-decrypted-output");
    if (fileInput) fileInput.addEventListener("change", loadPrivateKey);
    if (passwordInput) passwordInput.addEventListener("change", loadPrivateKey);
    if (decryptButton) decryptButton.addEventListener("click", decryptLastResponse);
    if (requestButton) requestButton.addEventListener("click", buildEncryptedRequestWrapper);

    var swaggerRoot = document.getElementById("swagger-ui");
    swaggerObserver = new MutationObserver(function () {
      if (isPlainDocumentService()) {
        var plainPanel = document.getElementById("doors-crypto-panel");
        if (plainPanel) plainPanel.hidden = true;
        return;
      }
      placeCryptoPanelBelowExecute();
      placeDecryptButtonBelowResponseHeaders();
    });
    swaggerObserver.observe(swaggerRoot, { childList: true, subtree: true });
    placeCryptoPanelBelowExecute();
  });

  window.addEventListener("doors-swagger-ready", function () {
    if (!isPlainDocumentService()) return;
    if (swaggerObserver) {
      swaggerObserver.disconnect();
      swaggerObserver = null;
    }
    var panel = document.getElementById("doors-crypto-panel");
    if (panel) panel.hidden = true;
  });
})();
