/*
 *  Copyright (c) 2022 Bayerische Motoren Werke Aktiengesellschaft (BMW AG)
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Bayerische Motoren Werke Aktiengesellschaft (BMW AG) - initial API and implementation
 *
 */

package org.eclipse.edc.test.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import org.eclipse.edc.connector.contract.spi.ContractId;
import org.eclipse.edc.connector.policy.spi.PolicyDefinition;
import org.eclipse.edc.jsonld.TitaniumJsonLd;
import org.eclipse.edc.jsonld.spi.JsonLd;
import org.eclipse.edc.jsonld.util.JacksonJsonLd;
import org.eclipse.edc.policy.model.PolicyRegistrationTypes;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.monitor.ConsoleMonitor;
import org.eclipse.edc.spi.types.TypeManager;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.spi.types.domain.edr.EndpointDataReference;
import org.eclipse.edc.sql.testfixtures.PostgresqlLocalInstance;
import org.hamcrest.Matcher;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static java.io.File.separator;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.eclipse.edc.connector.contract.spi.types.negotiation.ContractNegotiationStates.FINALIZED;
import static org.eclipse.edc.jsonld.spi.JsonLdKeywords.ID;
import static org.eclipse.edc.jsonld.spi.JsonLdKeywords.TYPE;
import static org.eclipse.edc.jsonld.spi.PropertyAndTypeNames.DCAT_DATASET_ATTRIBUTE;
import static org.eclipse.edc.jsonld.spi.PropertyAndTypeNames.ODRL_POLICY_ATTRIBUTE;
import static org.eclipse.edc.junit.testfixtures.TestUtils.getFreePort;
import static org.eclipse.edc.spi.CoreConstants.EDC_NAMESPACE;
import static org.eclipse.edc.spi.system.ServiceExtensionContext.PARTICIPANT_ID;

public class Participant {

    private static final String PROTOCOL_PATH = "/protocol";

    private final Duration timeout = Duration.ofSeconds(30);

    private final URI controlPlane = URI.create("http://localhost:" + getFreePort());
    private final URI controlPlaneControl = URI.create("http://localhost:" + getFreePort() + "/control");
    private final URI controlPlaneManagement = URI.create("http://localhost:" + getFreePort() + "/api/management");
    private final URI dataPlane = URI.create("http://localhost:" + getFreePort());
    private final URI dataPlaneControl = URI.create("http://localhost:" + getFreePort() + "/control");
    private final URI dataPlanePublic = URI.create("http://localhost:" + getFreePort() + "/public");
    private final URI backendService = URI.create("http://localhost:" + getFreePort());
    private final URI protocolEndpoint = URI.create("http://localhost:" + getFreePort());
    private final String connectorId = "urn:connector:" + UUID.randomUUID();
    private final String name;
    private final String participantId;

    private final TypeManager typeManager = new TypeManager();
    private final ObjectMapper objectMapper = JacksonJsonLd.createObjectMapper();
    private final JsonLd jsonLd;

    public Participant(String name, String participantId) {
        this.name = name;
        this.participantId = participantId;
        jsonLd = new TitaniumJsonLd(new ConsoleMonitor());
        PolicyRegistrationTypes.TYPES.forEach(typeManager::registerTypes);
    }

    public void createAsset(String assetId, Map<String, String> dataAddressProperties) {
        var asset = Map.of(
                "asset", Map.of(
                        "id", assetId,
                        "properties", Map.of(
                                EDC_NAMESPACE + "id", assetId,
                                EDC_NAMESPACE + "description", "description"
                        )
                ),
                "dataAddress", Map.of(
                        "properties", dataAddressProperties
                )
        );

        given()
                .baseUri(controlPlaneManagement.toString())
                .contentType(JSON)
                .body(asset)
                .when()
                .post("/assets")
                .then()
                .statusCode(200)
                .contentType(JSON);
    }

    public void createPolicy(PolicyDefinition policyDefinition) {
        given()
                .baseUri(controlPlaneManagement.toString())
                .contentType(JSON)
                .body(policyDefinition)
                .when()
                .post("/policydefinitions")
                .then()
                .statusCode(200)
                .contentType(JSON);
    }

    public String createPolicyDefinition(JsonObject policy) {
        var requestBody = Json.createObjectBuilder()
                .add(TYPE, "PolicyDefinitionDto")
                .add(EDC_NAMESPACE + "policy", policy)
                .build();

        return given()
                .baseUri(controlPlaneManagement.toString())
                .contentType(JSON)
                .body(requestBody)
                .when()
                .post("/v2/policydefinitions")
                .then()
                .statusCode(200)
                .contentType(JSON)
                .extract().jsonPath().getString(ID);
    }

    public void createContractDefinition(String assetId, String definitionId, String accessPolicyId, String contractPolicyId) {
        var requestBody = Json.createObjectBuilder()
                .add(ID, definitionId)
                .add(TYPE, EDC_NAMESPACE + "ContractDefinition")
                .add(EDC_NAMESPACE + "accessPolicyId", accessPolicyId)
                .add(EDC_NAMESPACE + "contractPolicyId", contractPolicyId)
                .add(EDC_NAMESPACE + "criteria", Json.createArrayBuilder()
                        .add(Json.createObjectBuilder()
                                .add(TYPE, "CriterionDto")
                                .add(EDC_NAMESPACE + "operandLeft", EDC_NAMESPACE + "id")
                                .add(EDC_NAMESPACE + "operator", "=")
                                .add(EDC_NAMESPACE + "operandRight", assetId)
                                .build())
                        .build())
                .build();

        given()
                .baseUri(controlPlaneManagement.toString())
                .contentType(JSON)
                .body(requestBody)
                .when()
                .post("/v2/contractdefinitions")
                .then()
                .statusCode(200)
                .contentType(JSON);
    }

    /**
     * Start contract negotiation, waits for agreement and returns the agreement id
     */
    public String negotiateContract(Participant provider, String offerId, String assetId, JsonObject policy) {
        var requestBody = Json.createObjectBuilder()
                .add(TYPE, EDC_NAMESPACE + "NegotiationInitiateRequestDto")
                .add(EDC_NAMESPACE + "connectorId", "urn:connector:provider")
                .add(EDC_NAMESPACE + "consumerId", participantId)
                .add(EDC_NAMESPACE + "providerId", provider.participantId)
                .add(EDC_NAMESPACE + "connectorAddress", provider.protocolEndpoint + PROTOCOL_PATH)
                .add(EDC_NAMESPACE + "protocol", "dataspace-protocol-http")
                .add(EDC_NAMESPACE + "offer", Json.createObjectBuilder()
                        .add(EDC_NAMESPACE + "offerId", offerId)
                        .add(EDC_NAMESPACE + "assetId", assetId)
                        .add(EDC_NAMESPACE + "policy", jsonLd.compact(policy).getContent())
                )
                .build();

        var negotiationId = given()
                .baseUri(controlPlaneManagement.toString())
                .contentType(JSON)
                .body(requestBody)
                .when()
                .post("/v2/contractnegotiations")
                .then()
                .statusCode(200)
                .extract().body().jsonPath().getString(ID);

        await().atMost(timeout).untilAsserted(() -> {
            var state = getContractNegotiationState(negotiationId);
            assertThat(state).isEqualTo(FINALIZED.name());
        });

        return getContractAgreementId(negotiationId);
    }

    public String getContractAgreementId(String negotiationId) {
        var contractAgreementId = new AtomicReference<String>();

        await().atMost(timeout).untilAsserted(() -> {
            var agreementId = getContractNegotiationField(negotiationId, "contractAgreementId");
            assertThat(agreementId).isNotNull().isInstanceOf(String.class);

            contractAgreementId.set(agreementId);
        });

        var id = contractAgreementId.get();
        assertThat(id).isNotEmpty();
        return id;
    }

    public String dataRequest(String id, String contractAgreementId, String assetId, Participant provider, DataAddress dataAddress) {
        var request = Map.of(
                "id", id,
                "contractId", contractAgreementId,
                "assetId", assetId,
                "connectorId", "provider",
                "connectorAddress", provider.protocolEndpoint + PROTOCOL_PATH,
                "protocol", "dataspace-protocol-http",
                "dataDestination", dataAddress,
                "managedResources", false
        );

        return given()
                .baseUri(controlPlaneManagement.toString())
                .contentType(JSON)
                .body(request)
                .when()
                .post("/transferprocess")
                .then()
                .statusCode(200)
                .extract().body().jsonPath().getString("id");
    }

    public String initiateTransfer(String contractId, String assetId, Participant provider, JsonObject destination) {
        var requestBody = Json.createObjectBuilder()
                .add(TYPE, EDC_NAMESPACE + "TransferRequestDto")
                .add(EDC_NAMESPACE + "dataDestination", destination)
                .add(EDC_NAMESPACE + "protocol", "dataspace-protocol-http")
                .add(EDC_NAMESPACE + "assetId", assetId)
                .add(EDC_NAMESPACE + "contractId", contractId)
                .add(EDC_NAMESPACE + "connectorAddress", provider.protocolEndpoint + PROTOCOL_PATH)
                .build();

        return given()
                .baseUri(controlPlaneManagement.toString())
                .contentType(JSON)
                .body(requestBody)
                .when()
                .post("/v2/transferprocesses")
                .then()
                .statusCode(200)
                .extract().body().jsonPath().getString(ID);
    }

    public String getContractNegotiationState(String id) {
        return given()
                .baseUri(controlPlaneManagement.toString())
                .contentType(JSON)
                .when()
                .get("/v2/contractnegotiations/{id}/state", id)
                .then()
                .statusCode(200)
                .extract().body().jsonPath().getString("'edc:state'");
    }

    public String getTransferProcessState(String transferProcessId) {
        return given()
                .baseUri(controlPlaneManagement.toString())
                .contentType(JSON)
                .when()
                .get("/transferprocess/{id}/state", transferProcessId)
                .then()
                .statusCode(200)
                .extract().body().jsonPath().getString("state");
    }

    public EndpointDataReference getDataReference(String id) {
        var dataReference = new AtomicReference<EndpointDataReference>();

        await().atMost(timeout).untilAsserted(() -> {
            var result = given()
                    .baseUri(backendService.toString())
                    .when()
                    .get("/api/consumer/dataReference/{id}", id)
                    .then()
                    .statusCode(200)
                    .extract()
                    .body()
                    .as(EndpointDataReference.class);
            dataReference.set(result);
        });

        return dataReference.get();
    }

    public void pullData(EndpointDataReference edr, Map<String, String> queryParams, Matcher<String> bodyMatcher) {
        given()
                .baseUri(edr.getEndpoint())
                .header(edr.getAuthKey(), edr.getAuthCode())
                .queryParams(queryParams)
                .when()
                .get()
                .then()
                .statusCode(200)
                .body("message", bodyMatcher);
    }

    public URI backendService() {
        return backendService;
    }

    public void registerDataPlane() {
        var body = Map.of(
                "id", UUID.randomUUID().toString(),
                "url", dataPlaneControl + "/transfer",
                "allowedSourceTypes", List.of("HttpData", "HttpProvision", "Kafka"),
                "allowedDestTypes", List.of("HttpData", "HttpProvision", "HttpProxy", "Kafka"),
                "properties", Map.of("publicApiUrl", dataPlanePublic.toString())
        );

        given()
                .baseUri(controlPlaneManagement.toString())
                .contentType(JSON)
                .body(body)
                .when()
                .post("/instances")
                .then()
                .statusCode(204);
    }

    public JsonArray getCatalogDatasets(Participant provider) {
        var datasetReference = new AtomicReference<JsonArray>();
        var requestBody = Map.of(
                "@type", "CatalogRequest",
                EDC_NAMESPACE + "providerUrl", provider.protocolEndpoint() + PROTOCOL_PATH,
                EDC_NAMESPACE + "protocol", "dataspace-protocol-http"
        );

        await().atMost(timeout).untilAsserted(() -> {
            var response = given()
                    .baseUri(controlPlaneManagement.toString())
                    .contentType(JSON)
                    .when()
                    .body(requestBody)
                    .post("/v2/catalog/request")
                    .then()
                    .statusCode(200)
                    .extract().body().asString();

            var responseBody = objectMapper.readValue(response, JsonObject.class);

            var catalog = jsonLd.expand(responseBody).orElseThrow(f -> new EdcException(f.getFailureDetail()));

            var datasets = catalog.getJsonArray(DCAT_DATASET_ATTRIBUTE);
            assertThat(datasets).hasSizeGreaterThan(0);

            datasetReference.set(datasets);
        });

        return datasetReference.get();
    }

    public JsonObject getDatasetForAsset(String assetId, Participant provider) {
        var datasets = getCatalogDatasets(provider);
        return datasets.stream()
                .map(JsonValue::asJsonObject)
                .filter(it -> assetId.equals(getContractId(it).assetIdPart()))
                .findFirst()
                .orElseThrow(() -> new EdcException(format("No dataset for asset %s in the catalog", assetId)));
    }

    private ContractId getContractId(JsonObject dataset) {
        var id = dataset.getJsonArray(ODRL_POLICY_ATTRIBUTE).get(0).asJsonObject().getString(ID);
        return ContractId.parse(id);
    }

    public URI protocolEndpoint() {
        return protocolEndpoint;
    }

    public Map<String, String> controlPlaneConfiguration() {
        return new HashMap<>() {
            {
                put(PARTICIPANT_ID, participantId);
                put("web.http.port", String.valueOf(controlPlane.getPort()));
                put("web.http.path", "/api");
                put("web.http.protocol.port", String.valueOf(protocolEndpoint.getPort()));
                put("web.http.protocol.path", PROTOCOL_PATH);
                put("web.http.management.port", String.valueOf(controlPlaneManagement.getPort()));
                put("web.http.management.path", controlPlaneManagement.getPath());
                put("web.http.control.port", String.valueOf(controlPlaneControl.getPort()));
                put("web.http.control.path", controlPlaneControl.getPath());
                put("edc.dsp.callback.address", "http://localhost:" + protocolEndpoint.getPort() + PROTOCOL_PATH);
                put("edc.ids.id", connectorId);
                put("edc.vault", resourceAbsolutePath(name + "-vault.properties"));
                put("edc.keystore", resourceAbsolutePath("certs/cert.pfx"));
                put("edc.keystore.password", "123456");
                put("ids.webhook.address", protocolEndpoint.toString());
                put("edc.receiver.http.endpoint", backendService + "/api/consumer/dataReference");
                put("edc.transfer.proxy.token.signer.privatekey.alias", "1");
                put("edc.transfer.proxy.token.verifier.publickey.alias", "public-key");
                put("edc.transfer.proxy.endpoint", dataPlanePublic.toString());
                put("edc.transfer.send.retry.limit", "1");
                put("edc.transfer.send.retry.base-delay.ms", "100");
                put("edc.negotiation.consumer.send.retry.limit", "1");
                put("edc.negotiation.provider.send.retry.limit", "1");
                put("edc.negotiation.consumer.send.retry.base-delay.ms", "100");
                put("edc.negotiation.provider.send.retry.base-delay.ms", "100");

                put("provisioner.http.entries.default.provisioner.type", "provider");
                put("provisioner.http.entries.default.endpoint", backendService + "/api/provision");
                put("provisioner.http.entries.default.data.address.type", "HttpProvision");
            }
        };
    }

    public Map<String, String> controlPlanePostgresConfiguration() {
        var baseConfiguration = controlPlaneConfiguration();

        var postgresConfiguration = new HashMap<String, String>() {
            {
                put("edc.datasource.asset.name", "asset");
                put("edc.datasource.asset.url", jdbcUrl());
                put("edc.datasource.asset.user", PostgresqlLocalInstance.USER);
                put("edc.datasource.asset.password", PostgresqlLocalInstance.PASSWORD);
                put("edc.datasource.contractdefinition.name", "contractdefinition");
                put("edc.datasource.contractdefinition.url", jdbcUrl());
                put("edc.datasource.contractdefinition.user", PostgresqlLocalInstance.USER);
                put("edc.datasource.contractdefinition.password", PostgresqlLocalInstance.PASSWORD);
                put("edc.datasource.contractnegotiation.name", "contractnegotiation");
                put("edc.datasource.contractnegotiation.url", jdbcUrl());
                put("edc.datasource.contractnegotiation.user", PostgresqlLocalInstance.USER);
                put("edc.datasource.contractnegotiation.password", PostgresqlLocalInstance.PASSWORD);
                put("edc.datasource.policy.name", "policy");
                put("edc.datasource.policy.url", jdbcUrl());
                put("edc.datasource.policy.user", PostgresqlLocalInstance.USER);
                put("edc.datasource.policy.password", PostgresqlLocalInstance.PASSWORD);
                put("edc.datasource.transferprocess.name", "transferprocess");
                put("edc.datasource.transferprocess.url", jdbcUrl());
                put("edc.datasource.transferprocess.user", PostgresqlLocalInstance.USER);
                put("edc.datasource.transferprocess.password", PostgresqlLocalInstance.PASSWORD);
            }
        };
        baseConfiguration.putAll(postgresConfiguration);

        return baseConfiguration;
    }

    public Map<String, String> controlPlaneCosmosDbConfiguration(String uniqueTestName) {
        var baseConfiguration = controlPlaneConfiguration();
        var cosmosDbConfiguration = new HashMap<String, String>() {
            {
                put("edc.assetindex.cosmos.account-name", "test");
                put("edc.assetindex.cosmos.database-name", uniqueTestName);
                put("edc.assetindex.cosmos.container-name", name + "-assetindex");
                put("edc.contractdefinitionstore.cosmos.account-name", "test");
                put("edc.contractdefinitionstore.cosmos.database-name", uniqueTestName);
                put("edc.contractdefinitionstore.cosmos.container-name", name + "-contractdefinitionstore");
                put("edc.contractnegotiationstore.cosmos.account-name", "test");
                put("edc.contractnegotiationstore.cosmos.database-name", uniqueTestName);
                put("edc.contractnegotiationstore.cosmos.container-name", name + "-contractnegotiationstore");
                put("edc.node.directory.cosmos.account.name", "test");
                put("edc.node.directory.cosmos.database.name", uniqueTestName);
                put("edc.node.directory.cosmos.container.name", name + "-nodedirectory");
                put("edc.policystore.cosmos.account-name", "test");
                put("edc.policystore.cosmos.database-name", uniqueTestName);
                put("edc.policystore.cosmos.container-name", name + "-policystore");
                put("edc.transfer-process-store.cosmos.account.name", "test");
                put("edc.transfer-process-store.database.name", uniqueTestName);
                put("edc.transfer-process-store.cosmos.container-name", name + "-transfer-process-store");
            }
        };
        baseConfiguration.putAll(cosmosDbConfiguration);

        return baseConfiguration;
    }

    @NotNull
    public String jdbcUrl() {
        return PostgresqlLocalInstance.JDBC_URL_PREFIX + name;
    }

    public Map<String, String> dataPlaneConfiguration() {
        return new HashMap<>() {
            {
                put("web.http.port", String.valueOf(dataPlane.getPort()));
                put("web.http.path", "/api");
                put("web.http.public.port", String.valueOf(dataPlanePublic.getPort()));
                put("web.http.public.path", "/public");
                put("web.http.control.port", String.valueOf(dataPlaneControl.getPort()));
                put("web.http.control.path", dataPlaneControl.getPath());
                put("edc.vault", resourceAbsolutePath(name + "-vault.properties"));
                put("edc.keystore", resourceAbsolutePath("certs/cert.pfx"));
                put("edc.keystore.password", "123456");
                put("edc.dataplane.token.validation.endpoint", controlPlaneControl + "/token");
            }
        };
    }

    public String getName() {
        return name;
    }

    private String getContractNegotiationField(String negotiationId, String fieldName) {
        return given()
                .baseUri(controlPlaneManagement.toString())
                .contentType(JSON)
                .when()
                .get("/v2/contractnegotiations/{id}", negotiationId)
                .then()
                .statusCode(200)
                .extract().body().jsonPath()
                .getString(format("'edc:%s'", fieldName));
    }

    @NotNull
    private String resourceAbsolutePath(String filename) {
        return System.getProperty("user.dir") + separator + "build" + separator + "resources" + separator + "test" + separator + filename;
    }
}
