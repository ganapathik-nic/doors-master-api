package org.gepnic.doors.masterapi.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DynamicOrchestrationOpenApiCustomizer implements OpenApiCustomizer {

    @Override
    public void customise(OpenAPI openAPI) {
        if (openAPI == null || openAPI.getPaths() == null) return;
        registerBaseSchemas(openAPI);

        openAPI.getPaths().forEach((path, pathItem) -> {
            if (path == null || !path.contains("/orchestrate/") || pathItem.getPost() == null) return;

            Schema<Object> requestChoice = new ObjectSchema();
            requestChoice.setOneOf(List.of(
                    ref("PlainOrchestrationRequest"),
                    ref("EncryptedRequestWrapper")
            ));
            io.swagger.v3.oas.models.media.MediaType requestMedia =
                    new io.swagger.v3.oas.models.media.MediaType().schema(requestChoice);
            pathItem.getPost().setRequestBody(new RequestBody()
                    .required(true)
                    .description("Plain request for non-encrypted profiles or encrypted transport wrapper.")
                    .content(new Content().addMediaType("application/json", requestMedia)));

            Schema<Object> responseChoice = new ObjectSchema();
            responseChoice.setOneOf(List.of(ref("EncryptedResponse"), ref("OrchestrationResponse")));
            pathItem.getPost().getResponses().addApiResponse("200", new ApiResponse()
                    .description("Encrypted response, or plain response for a non-encrypted client profile")
                    .content(new Content().addMediaType("application/json",
                            new io.swagger.v3.oas.models.media.MediaType().schema(responseChoice))));
            addError(pathItem.getPost().getResponses(), "400", "Malformed request or encryption wrapper");
            addError(pathItem.getPost().getResponses(), "401", "Missing or invalid API key");
            addError(pathItem.getPost().getResponses(), "403", "Client is not authorized for this template");
            addError(pathItem.getPost().getResponses(), "404", "Unknown uniqueName");
            addError(pathItem.getPost().getResponses(), "422", "Invalid template parameters");
            addError(pathItem.getPost().getResponses(), "429", "Rate limit exceeded");
            addError(pathItem.getPost().getResponses(), "500", "Internal orchestration failure");

            pathItem.getPost().setDescription(
                    "Executes the dynamic template selected by `uniqueName`. " +
                    "See the Template Catalogue in this page for parameter and decrypted-response contracts. " +
                    "Encrypted transport uses RSA wrapped AES data and a client signature.");
        });
    }

    private void registerBaseSchemas(OpenAPI openAPI) {
        if (openAPI.getComponents() == null) openAPI.setComponents(new io.swagger.v3.oas.models.Components());

        ObjectSchema params = new ObjectSchema();
        params.setAdditionalProperties(true);
        ObjectSchema plain = new ObjectSchema();
        plain.addProperty("agentIds", new ArraySchema().items(new StringSchema()).minItems(1));
        plain.addProperty("params", params);
        plain.setRequired(List.of("agentIds", "params"));

        ObjectSchema encrypted = new ObjectSchema();
        encrypted.addProperty("encryptedKey", byteString("RSA-encrypted 256-bit AES session key"));
        encrypted.addProperty("iv", byteString("Required Base64-encoded 12-byte AES-GCM nonce"));
        encrypted.addProperty("secureData", byteString(
                "AES-256-GCM encrypted UTF-8 request JSON, including the 128-bit authentication tag"));
        encrypted.addProperty("signature", byteString("RSA signature over the plaintext request"));
        encrypted.setRequired(List.of("encryptedKey", "iv", "secureData", "signature"));

        ObjectSchema encryptedResponse = new ObjectSchema();
        encryptedResponse.addProperty("encryptedKey", byteString("AES response key encrypted with the client public key"));
        encryptedResponse.addProperty("iv", byteString("AES-CBC initialization vector"));
        encryptedResponse.addProperty("secureData", byteString("Encrypted response JSON"));
        encryptedResponse.setRequired(List.of("encryptedKey", "iv", "secureData"));

        ObjectSchema agentPayload = new ObjectSchema();
        agentPayload.addProperty("GePNIC_Instance_ID", new StringSchema());
        agentPayload.addProperty("type", new StringSchema()._enum(List.of("json")));
        agentPayload.addProperty("value", new ObjectSchema().additionalProperties(true));
        ObjectSchema orchestration = new ObjectSchema();
        orchestration.addProperty("status", new StringSchema()._enum(List.of("SUCCESS", "ERROR")));
        orchestration.addProperty("message", new StringSchema());
        orchestration.addProperty("payload", new ArraySchema().items(agentPayload));

        ObjectSchema error = new ObjectSchema();
        error.addProperty("status", new StringSchema().example("ERROR"));
        error.addProperty("message", new StringSchema());
        error.addProperty("correlationId", new StringSchema());

        openAPI.getComponents()
                .addSchemas("PlainOrchestrationRequest", plain)
                .addSchemas("EncryptedRequestWrapper", encrypted)
                .addSchemas("EncryptedResponse", encryptedResponse)
                .addSchemas("OrchestrationResponse", orchestration)
                .addSchemas("ApiError", error);
    }

    private Schema<?> byteString(String description) {
        return new StringSchema().format("byte").description(description);
    }

    private Schema<Object> ref(String name) {
        return new Schema<>().$ref("#/components/schemas/" + name);
    }

    private void addError(io.swagger.v3.oas.models.responses.ApiResponses responses,
                          String code, String description) {
        responses.addApiResponse(code, new ApiResponse()
                .description(description)
                .content(new Content().addMediaType("application/json",
                        new io.swagger.v3.oas.models.media.MediaType().schema(ref("ApiError")))));
    }
}
