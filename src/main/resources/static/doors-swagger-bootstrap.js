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
      var response = await fetch("api/v1/master/gateway/swagger-sessions/exchange", {
        method: "POST",
        cache: "no-store",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({ launchToken: launchToken })
      });
      var result = await response.json();
      if (!response.ok || !result.payload) throw new Error(result.message || "HTTP " + response.status);

      var sessionToken = result.payload.swaggerSessionToken;
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
      showFailure(error.message || error);
    }
  }

  bootstrap();
})();
