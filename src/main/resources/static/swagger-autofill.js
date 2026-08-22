/**
 * 🚀 DOORS SWAGGER UI ZERO-CLICK AUTO-FILL INTERCEPTOR
 */
(function () {
    console.log("⚡ [DOORS-SWAGGER] Interceptor active.");

    function setReactValue(element, val) {
        if (!element) return;
        const valueSetter = Object.getOwnPropertyDescriptor(element, 'value')?.set;
        const prototype = Object.getPrototypeOf(element);
        const prototypeValueSetter = Object.getOwnPropertyDescriptor(prototype, 'value')?.set;

        if (prototypeValueSetter && valueSetter !== prototypeValueSetter) {
            prototypeValueSetter.call(element, val);
        } else if (valueSetter) {
            valueSetter.call(element, val);
        } else {
            element.value = val;
        }

        if (element._valueTracker) {
            element._valueTracker.setValue('');
        }

        element.dispatchEvent(new Event('input', { bubbles: true }));
        element.dispatchEvent(new Event('change', { bubbles: true }));
        element.dispatchEvent(new Event('blur', { bubbles: true }));
    }

    function processAutoFill() {
        const launchConfig = window.doorsSwaggerLaunchConfig || {};
        const apiKey = launchConfig.apiKey;
        const uniqueName = launchConfig.uniqueName;
        const rawBody = launchConfig.body ? JSON.stringify(launchConfig.body) : null;

        if (!apiKey && !uniqueName && !rawBody) {
            console.log("ℹ️ [DOORS-SWAGGER] No target URL parameters detected.");
            return;
        }

        console.log("DOORS Swagger launch configuration loaded for", uniqueName);

        let fillAttempts = 0;
        const timer = setInterval(() => {
            fillAttempts++;

            // 1. Click "Try it out" button
            const tryBtn = document.querySelector('.btn.try-out__btn');
            if (tryBtn && !document.querySelector('.btn.execute')) {
                tryBtn.click();
            }

            // 2. Target Exact Inputs
            const pathInput = document.querySelector('input[placeholder="uniqueName"]') ||
                              document.querySelector('tr[data-param-name="uniqueName"] input');

            const apiKeyInput = document.querySelector('input[placeholder="X-API-KEY"]') ||
                                document.querySelector('tr[data-param-name="X-API-KEY"] input');

            const bodyInput = document.querySelector('textarea.body-param__text') ||
                              document.querySelector('.body-param__text') ||
                              document.querySelector('textarea');

            let pathDone = false;
            let keyDone = false;
            let bodyDone = false;

            if (pathInput && !pathInput.disabled && uniqueName) {
                setReactValue(pathInput, uniqueName);
                pathDone = true;
            } else if (!uniqueName) pathDone = true;

            if (apiKeyInput && !apiKeyInput.disabled && apiKey) {
                setReactValue(apiKeyInput, apiKey);
                keyDone = true;
            } else if (!apiKey) keyDone = true;

            if (bodyInput && !bodyInput.disabled && rawBody) {
                try {
                    const formatted = JSON.stringify(JSON.parse(rawBody), null, 2);
                    setReactValue(bodyInput, formatted);
                } catch (err) {
                    setReactValue(bodyInput, rawBody);
                }
                bodyDone = true;
            } else if (!rawBody) bodyDone = true;

            if ((pathDone && keyDone && bodyDone) || fillAttempts >= 40) {
                clearInterval(timer);
                if (pathDone && keyDone && bodyDone) {
                    console.log("🎉 [DOORS-SWAGGER] Auto-fill executed successfully!");
                }
            }
        }, 250);
    }

    window.addEventListener('doors-swagger-ready', function () {
        setTimeout(processAutoFill, 500);
    });
})();
