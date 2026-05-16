const CryptoJS = require('crypto-js');

try {
    const response = pm.response.json();
    
    if (!response || !response.secureData) {
        console.error("❌ ERROR: 'secureData' not found.");
    } else {
        const encryptedBase64 = response.secureData;

        // 🚀 THE FIX: Get the API Key from the Request Header
        const apiKey = pm.request.headers.get("X-API-KEY");
        
        if (!apiKey) {
            throw new Error("X-API-KEY header is missing from your request!");
        }

        // Use the first 16 characters exactly like the Java backend does
        const keyString = apiKey.substring(0, 16); 
        const key = CryptoJS.enc.Utf8.parse(keyString);

        console.log("🛠️ Using Decryption Key Segment:", keyString);

        // 3. Decrypt
        const decrypted = CryptoJS.AES.decrypt(encryptedBase64, key, {
            mode: CryptoJS.mode.ECB,
            padding: CryptoJS.pad.Pkcs7
        });

        // 4. Convert to String
        const clearText = decrypted.toString(CryptoJS.enc.Utf8);

        if (!clearText) {
            // This is where the Hex diagnostic helps if it fails
            console.log("❌ Decrypted Hex:", decrypted.toString());
            throw new Error("Malformed UTF-8: The key segment used doesn't match the server's key.");
        }

        const finalJson = JSON.parse(clearText);
        console.log("🔓 SUCCESS! Decrypted Data:", finalJson);
    }
} catch (e) {
    console.error("🚨 DECRYPTION FAILED:", e.message);
}