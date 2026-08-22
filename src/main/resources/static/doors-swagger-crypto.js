(function () {
  "use strict";

  var privateKey = null;
  var lastEncryptedEnvelope = null;
  var responseDecryptAction = null;
  var decryptedOutput = null;

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
      setStatus("Encrypted response decrypted successfully", "ready");
    } catch (error) {
      var failureMessage = "Response decryption failed: " + (error.message || error);
      if (output) {
        output.textContent = failureMessage;
        output.hidden = false;
      }
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

  async function buildEncryptedRequestWrapper() {
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
      JSON.parse(rawBody);
      var masterPublicKey = await fetchMasterPublicKey();
      var dynamicAesKey = forge.random.getBytesSync(32);
      var requestAesKey = dynamicAesKey.substring(0, 16);

      var aesCipher = forge.cipher.createCipher("AES-ECB", requestAesKey);
      aesCipher.start();
      aesCipher.update(forge.util.createBuffer(forge.util.encodeUtf8(rawBody)));
      if (!aesCipher.finish()) throw new Error("Unable to encrypt the request payload.");

      var digest = forge.md.sha512.create();
      digest.update(rawBody, "utf8");

      var wrapper = {
        encryptedKey: forge.util.encode64(
          masterPublicKey.encrypt(dynamicAesKey, "RSAES-PKCS1-V1_5")
        ),
        secureData: forge.util.encode64(aesCipher.output.getBytes()),
        signature: forge.util.encode64(privateKey.sign(digest))
      };

      var output = document.getElementById("doors-encrypted-request-output");
      output.textContent =
        "ENCRYPTED REQUEST JSON WRAPPER\n\n" + JSON.stringify(wrapper, null, 2);
      output.hidden = false;
      placeRequestWrapperBeforeResponse(output);
      placeFetchButtonBelowRequestWrapper(output);
      setStatus("Encrypted request wrapper built successfully", "ready");
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
    var swaggerObserver = new MutationObserver(function () {
      placeCryptoPanelBelowExecute();
      placeDecryptButtonBelowResponseHeaders();
    });
    swaggerObserver.observe(swaggerRoot, { childList: true, subtree: true });
    placeCryptoPanelBelowExecute();
  });
})();
