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
        const pathParameterName = launchConfig.pathParameterName || "uniqueName";
        const pathValue = launchConfig.pathValue || uniqueName;
        const secondPathParameterName = launchConfig.secondPathParameterName;
        const secondPathValue = launchConfig.secondPathValue;
        const operationPath = launchConfig.operationPath;
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
            const operationBlock = operationPath
                ? document.querySelector('.opblock[data-path="' + operationPath + '"]')
                : document;
            const tryBtn = operationBlock && operationBlock.querySelector('.btn.try-out__btn');
            if (tryBtn && !operationBlock.querySelector('.btn.execute')) {
                tryBtn.click();
            }

            // 2. Target Exact Inputs
            const pathInput = operationBlock && (operationBlock.querySelector('input[placeholder="' + pathParameterName + '"]') ||
                              operationBlock.querySelector('tr[data-param-name="' + pathParameterName + '"] input'));
            const secondPathInput = operationBlock && secondPathParameterName &&
                              (operationBlock.querySelector('input[placeholder="' + secondPathParameterName + '"]') ||
                               operationBlock.querySelector('tr[data-param-name="' + secondPathParameterName + '"] input'));

            const apiKeyInput = operationBlock && (operationBlock.querySelector('input[placeholder="X-API-KEY"]') ||
                                operationBlock.querySelector('tr[data-param-name="X-API-KEY"] input'));

            const bodyInput = operationBlock && (operationBlock.querySelector('textarea.body-param__text') ||
                              operationBlock.querySelector('.body-param__text') ||
                              operationBlock.querySelector('textarea'));

            let pathDone = false;
            let secondPathDone = false;
            let keyDone = false;
            let bodyDone = false;

            if (pathInput && !pathInput.disabled && pathValue) {
                setReactValue(pathInput, pathValue);
                pathDone = true;
            } else if (!pathValue) pathDone = true;

            if (secondPathInput && !secondPathInput.disabled && secondPathValue) {
                setReactValue(secondPathInput, secondPathValue);
                secondPathDone = true;
            } else if (!secondPathValue) secondPathDone = true;

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

            if ((pathDone && secondPathDone && keyDone && bodyDone) || fillAttempts >= 40) {
                clearInterval(timer);
                if (pathDone && secondPathDone && keyDone && bodyDone) {
                    console.log("🎉 [DOORS-SWAGGER] Auto-fill executed successfully!");
                }
            }
        }, 250);
    }

    window.addEventListener('doors-swagger-ready', function () {
        setTimeout(processAutoFill, 500);
    });
})();
