package com.cardprocess.portador.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cardprocess.shared.messaging.IssuanceMessage;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CardholderIssuanceIntegrationTest {

    private static final String QUEUE = "card-issuance-queue";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
                    .withServices(Service.SQS);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.cloud.aws.sqs.endpoint", () -> localstack.getEndpointOverride(Service.SQS).toString());
        registry.add("spring.cloud.aws.region.static", localstack::getRegion);
        registry.add("spring.cloud.aws.credentials.access-key", localstack::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", localstack::getSecretKey);
        registry.add("cardprocess.issuance.queue", () -> QUEUE);
    }

    @BeforeAll
    static void createQueue() throws Exception {
        localstack.execInContainer("awslocal", "sqs", "create-queue", "--queue-name", QUEUE);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SqsTemplate sqsTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void registeringCardholderEnqueuesIssuanceMessage() throws Exception {
        String credentials = "{\"username\":\"operator\",\"password\":\"secret123\"}";
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isCreated());

        MvcResult login = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString()).get("token").asText();

        UUID productId = UUID.randomUUID();
        String cardholder = "{\"name\":\"Maria Silva\",\"cpf\":\"39053344705\",\"birthDate\":\"1990-05-12\",\"productId\":\""
                + productId + "\"}";
        MvcResult created = mockMvc.perform(post("/cardholders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(cardholder))
                .andExpect(status().isCreated())
                .andReturn();
        UUID cardholderId = UUID.fromString(
                objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        Optional<Message<IssuanceMessage>> message = sqsTemplate.receive(
                from -> from.queue(QUEUE).pollTimeout(Duration.ofSeconds(10)), IssuanceMessage.class);

        assertThat(message).isPresent();
        assertThat(message.get().getPayload().cardholderId()).isEqualTo(cardholderId);
        assertThat(message.get().getPayload().productId()).isEqualTo(productId);
    }

    @Test
    void rejectsCardholderCreationWithoutToken() throws Exception {
        String cardholder = "{\"name\":\"Maria Silva\",\"cpf\":\"39053344705\",\"birthDate\":\"1990-05-12\",\"productId\":\""
                + UUID.randomUUID() + "\"}";
        mockMvc.perform(post("/cardholders")
                        .contentType(MediaType.APPLICATION_JSON).content(cardholder))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsDuplicateCpfWithConflictAndMaskedDetail() throws Exception {
        String credentials = "{\"username\":\"operator2\",\"password\":\"secret123\"}";
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString()).get("token").asText();

        String cardholder = "{\"name\":\"Joao Souza\",\"cpf\":\"52998224725\",\"birthDate\":\"1985-03-20\",\"productId\":\""
                + UUID.randomUUID() + "\"}";
        MvcResult created = mockMvc.perform(post("/cardholders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(cardholder))
                .andExpect(status().isCreated())
                .andReturn();
        UUID cardholderId = UUID.fromString(
                objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        Optional<Message<IssuanceMessage>> drained = sqsTemplate.receive(
                from -> from.queue(QUEUE).pollTimeout(Duration.ofSeconds(10)), IssuanceMessage.class);
        assertThat(drained).isPresent();
        assertThat(drained.get().getPayload().cardholderId()).isEqualTo(cardholderId);

        MvcResult conflict = mockMvc.perform(post("/cardholders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(cardholder))
                .andExpect(status().isConflict())
                .andReturn();
        String detail = objectMapper.readTree(conflict.getResponse().getContentAsString()).get("detail").asText();
        assertThat(detail).doesNotContain("52998224725");
    }
}
