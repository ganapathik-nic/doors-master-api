(function () {
  "use strict";

  function showFailure(message) {
    var host = document.getElementById("swagger-ui");
    if (host) {
      host.innerHTML = '<div style="margin:24px;padding:18px;border:1px solid #dc3545;' +
        'color:#842029;background:#f8d7da;font:14px sans-serif">' +
        "Swagger access denied: " + String(message || "Unable to establish a secure session") +
        "</div>";
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
      showFailure("Open this console from the authenticated DOORS DataManager screen.");
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
          credentials: "same-origin",
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

      window.ui = SwaggerUIBundle({
        url: "v3/api-docs",
        dom_id: "#swagger-ui",
        deepLinking: true,
        docExpansion: "full",
        operationsSorter: "alpha",
        presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
        requestInterceptor: function (request) {
          request.headers = request.headers || {};
          request.headers["X-DOORS-SWAGGER-SESSION"] = sessionToken;
          return request;
        },
        responseInterceptor: window.doorsDecryptResponse,
        layout: "StandaloneLayout"
      });
      window.dispatchEvent(new CustomEvent("doors-swagger-ready"));
    } catch (error) {
      window.doorsSwaggerLaunchConfig = undefined;
      window.doorsSwaggerSessionToken = undefined;
      window.doorsSwaggerGatewayBase = undefined;
      showFailure(error.message || error);
    }
  }

  bootstrap();
})();
