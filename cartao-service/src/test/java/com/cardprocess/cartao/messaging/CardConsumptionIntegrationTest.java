package com.cardprocess.cartao.messaging;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.cardprocess.cartao.domain.Card;
import com.cardprocess.cartao.domain.CardStatus;
import com.cardprocess.cartao.infrastructure.persistence.CardRepository;
import com.cardprocess.shared.messaging.IssuanceMessage;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
class CardConsumptionIntegrationTest {

    private static final String QUEUE = "card-issuance-queue";

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    static final LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8")).withServices(Service.SQS);
    static final WireMockServer wireMock = new WireMockServer(options().dynamicPort());

    static {
        postgres.start();
        redis.start();
        localstack.start();
        wireMock.start();
        try {
            localstack.execInContainer("awslocal", "sqs", "create-queue", "--queue-name", QUEUE);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to provision SQS queue", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.cloud.aws.sqs.endpoint", () -> localstack.getEndpointOverride(Service.SQS).toString());
        registry.add("spring.cloud.aws.region.static", localstack::getRegion);
        registry.add("spring.cloud.aws.credentials.access-key", localstack::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", localstack::getSecretKey);
        registry.add("cardprocess.issuance.queue", () -> QUEUE);
        registry.add("cardprocess.produto.base-url", wireMock::baseUrl);
    }

    @Autowired
    private SqsTemplate sqsTemplate;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void consumesMessageValidatesProductPersistsCardAndCachesProduct() throws Exception {
        UUID cardholderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        wireMock.stubFor(get(urlEqualTo("/products/" + productId))
                .willReturn(okJson(productJson(productId, "Black", "ATIVO"))));

        sqsTemplate.send(to -> to.queue(QUEUE)
                .payload(new IssuanceMessage(cardholderId, productId, correlationId)));

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(cardRepository.findByCardholderId(cardholderId)).isPresent());

        Card card = cardRepository.findByCardholderId(cardholderId).orElseThrow();
        assertThat(card.getStatus()).isEqualTo(CardStatus.ATIVO);

        mockMvc.perform(get("/cards/{id}", card.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.name", is("Black")));
        mockMvc.perform(get("/cards/{id}", card.getId()))
                .andExpect(status().isOk());

        wireMock.verify(1, getRequestedFor(urlEqualTo("/products/" + productId)));
    }

    @Test
    void doesNotCreateCardForNonExistentProduct() {
        UUID cardholderId = UUID.randomUUID();
        UUID missingProductId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        wireMock.stubFor(get(urlEqualTo("/products/" + missingProductId))
                .willReturn(aResponse().withStatus(404)));

        sqsTemplate.send(to -> to.queue(QUEUE)
                .payload(new IssuanceMessage(cardholderId, missingProductId, correlationId)));

        await().pollDelay(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(6))
                .untilAsserted(() -> assertThat(cardRepository.findByCardholderId(cardholderId)).isEmpty());
    }

    private static String productJson(UUID id, String name, String status) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"status\":\"" + status + "\"}";
    }
}
