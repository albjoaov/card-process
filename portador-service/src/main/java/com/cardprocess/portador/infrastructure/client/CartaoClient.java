package com.cardprocess.portador.infrastructure.client;

import com.cardprocess.portador.application.CardGateway;
import com.cardprocess.portador.application.CardView;
import com.cardprocess.portador.application.CardView.ProductView;
import com.cardprocess.portador.domain.DomainExceptions.CardServiceUnavailableException;
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
public class CartaoClient implements CardGateway {

    private final RestClient restClient;

    public CartaoClient(RestClient.Builder builder, @Value("${cardprocess.cartao.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public Optional<CardView> findByCardholder(UUID cardholderId) {
        try {
            CartaoCardResponse response = restClient.get()
                    .uri("/cards/by-cardholder/{id}", cardholderId)
                    .retrieve()
                    .body(CartaoCardResponse.class);
            return Optional.ofNullable(response).map(CartaoClient::toView);
        } catch (HttpClientErrorException.NotFound notFound) {
            return Optional.empty();
        } catch (RestClientException unavailable) {
            throw new CardServiceUnavailableException(unavailable);
        }
    }

    private static CardView toView(CartaoCardResponse response) {
        ProductView product = response.product() == null ? null
                : new ProductView(response.product().id(), response.product().name(), response.product().status());
        return new CardView(response.id(), response.maskedNumber(), response.status(), product);
    }
}
