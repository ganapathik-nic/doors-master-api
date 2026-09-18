(function () {
  "use strict";

  function showFailure(message) {
    var host = document.getElementById("swagger-ui");
    if (host) {
      var notice = document.createElement("div");
      notice.className = "doors-swagger-failure";
      notice.textContent = "Swagger access denied: " +
        String(message || "Unable to establish a secure session");
      host.replaceChildren(notice);
    }
  }

  function takeLaunchToken() {
    var hash = window.location.hash ? window.location.hash.substring(1) : "";
    var token = new URLSearchParams(hash).get("launchToken");
    var windowNamePrefix = "DOORS_SWAGGER_LAUNCH:";
    if (!token && window.name && window.name.indexOf(windowNamePrefix) === 0) {
      token = window.name.substring(windowNamePrefix.length);
    }
    window.name = "";
    window.history.replaceState(null, document.title, window.location.pathname);
    return token;
  }

  async function bootstrap() {
    var launchToken = takeLaunchToken();
    if (!launchToken) {
      showFailure("Open this console using Test API on the authenticated DOORS API client page.");
      return;
    }
    try {
      var prefixedGateway = "api/v1/master/gateway";
      var rootGateway = "/api/v1/master/gateway";
      var gatewayCandidates = window.location.pathname.indexOf("/swagger/") === 0
        ? [prefixedGateway, rootGateway]
        : [rootGateway];
      var response;
      var responseText = "";
      var result;
      var gatewayBase;

      for (var index = 0; index < gatewayCandidates.length; index += 1) {
        gatewayBase = gatewayCandidates[index];
        response = await fetch(gatewayBase + "/swagger-sessions/exchange", {
          method: "POST",
          cache: "no-store",
          credentials: "omit",
          headers: { "Content-Type": "application/json", Accept: "application/json" },
          body: JSON.stringify({ launchToken: launchToken })
        });
        responseText = await response.text();
        try {
          result = JSON.parse(responseText);
          break;
        } catch (_) {
          result = undefined;
          // A proxy-generated HTML 403/404 means this deployment uses the other
          // supported gateway mount. Never retry a JSON application response,
          // because a successful single-use launch may already be consumed.
          if (index + 1 >= gatewayCandidates.length ||
              (response.status !== 403 && response.status !== 404)) {
            throw new Error("Session exchange returned HTTP " + response.status +
              " with a non-JSON response. Verify the Swagger proxy route.");
          }
        }
      }
      if (!response.ok || !result.payload) throw new Error(result.message || "HTTP " + response.status);

      var sessionToken = result.payload.swaggerSessionToken;
      window.doorsSwaggerGatewayBase = gatewayBase;
      window.doorsSwaggerLaunchConfig = Object.freeze(result.payload.configuration || {});
      window.doorsSwaggerSessionToken = sessionToken;
      if (window.doorsSwaggerLaunchConfig.operationPath &&
          window.doorsSwaggerLaunchConfig.payloadMode !== "AES") {
        var cryptoPanel = document.getElementById("doors-crypto-panel");
        if (cryptoPanel) cryptoPanel.hidden = true;
      }

      var openApiResponse = await fetch("v3/api-docs", {
        cache: "no-store",
        credentials: "omit",
        headers: {
          Accept: "application/json",
          "X-DOORS-SWAGGER-SESSION": sessionToken
        }
      });
      if (!openApiResponse.ok) {
        throw new Error("Unable to load the DOORS OpenAPI definition: HTTP " + openApiResponse.status);
      }
      var openApiSpec = await openApiResponse.json();
      var selectedOperationPath = window.doorsSwaggerLaunchConfig.operationPath ||
        "/api/v1/master/gateway/orchestrate/{uniqueName}";
      var selectedPathItem = openApiSpec.paths && openApiSpec.paths[selectedOperationPath];
      if (!selectedPathItem) {
        throw new Error("The selected DOORS operation is not published in OpenAPI: " + selectedOperationPath);
      }
      openApiSpec.paths = Object.fromEntries([[selectedOperationPath, selectedPathItem]]);
      if (window.doorsSwaggerLaunchConfig.operationPath) {
        openApiSpec.tags = (openApiSpec.tags || []).filter(function (tag) {
          return tag && tag.name === "document-download-gateway-controller";
        });
      } else {
        openApiSpec.tags = (openApiSpec.tags || []).filter(function (tag) {
          return tag && tag.name !== "document-download-gateway-controller";
        });
      }

      window.ui = SwaggerUIBundle({
        spec: openApiSpec,
        dom_id: "#swagger-ui",
        deepLinking: true,
        docExpansion: "full",
        operationsSorter: "alpha",
        presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
        requestInterceptor: function (request) {
          // Machine calls authenticate with the scoped launch/API key, not the portal cookie.
          request.credentials = "omit";
          request.headers = request.headers || {};
          request.headers["X-DOORS-SWAGGER-SESSION"] = sessionToken;
          if (window.doorsSwaggerLaunchConfig.operationPath &&
              window.doorsSwaggerLaunchConfig.payloadMode === "AES" &&
              request.url && request.url.indexOf("/documents/services/") >= 0) {
            request.headers["X-DOORS-REQUIRE-ENCRYPTED-RESPONSE"] = "true";
          }
          return window.doorsPrepareRequest ? window.doorsPrepareRequest(request) : request;
        },
        responseInterceptor: window.doorsDecryptResponse,
        onComplete: function () {
          window.dispatchEvent(new CustomEvent("doors-swagger-ready"));
        },
        layout: "StandaloneLayout"
      });
    } catch (error) {
      window.doorsSwaggerLaunchConfig = undefined;
      window.doorsSwaggerSessionToken = undefined;
      window.doorsSwaggerGatewayBase = undefined;
      showFailure(error.message || error);
    }
  }

  bootstrap();
})();
