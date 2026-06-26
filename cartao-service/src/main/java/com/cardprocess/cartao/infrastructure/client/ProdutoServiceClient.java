package com.cardprocess.cartao.infrastructure.client;

import com.cardprocess.cartao.application.ProductOrigin;
import com.cardprocess.cartao.application.ProductSnapshot;
import com.cardprocess.cartao.domain.DomainExceptions.ProductServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class ProdutoServiceClient implements ProductOrigin {

    private static final String RESILIENCE_INSTANCE = "produtoService";

    private final RestClient restClient;

    public ProdutoServiceClient(RestClient.Builder builder, @Value("${cardprocess.produto.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    @CircuitBreaker(name = RESILIENCE_INSTANCE)
    @Retry(name = RESILIENCE_INSTANCE, fallbackMethod = "fetchFallback")
    public Optional<ProductSnapshot> fetch(UUID productId) {
        try {
            ProdutoProductResponse response = restClient.get()
                    .uri("/products/{id}", productId)
                    .retrieve()
                    .body(ProdutoProductResponse.class);
            return Optional.ofNullable(response)
                    .map(it -> new ProductSnapshot(it.id(), it.name(), it.status()));
        } catch (HttpClientErrorException.NotFound notFound) {
            return Optional.empty();
        } catch (RestClientException transportError) {
            throw new ProductServiceUnavailableException(transportError.getMessage(), transportError);
        }
    }

    @SuppressWarnings("unused")
    private Optional<ProductSnapshot> fetchFallback(UUID productId, Throwable cause) {
        throw new ProductServiceUnavailableException("retries exhausted or circuit open", cause);
    }
}
