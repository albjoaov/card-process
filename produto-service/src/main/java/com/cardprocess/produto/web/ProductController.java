package com.cardprocess.produto.web;

import com.cardprocess.produto.application.ProductService;
import com.cardprocess.produto.web.dto.CreateProductRequest;
import com.cardprocess.produto.web.dto.ProductResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        ProductResponse response = ProductResponse.from(productService.create(request.name()));
        return ResponseEntity.created(URI.create("/products/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    public ProductResponse getById(@PathVariable UUID id) {
        return ProductResponse.from(productService.getById(id));
    }

    @PatchMapping("/{id}/cancel")
    public ProductResponse cancel(@PathVariable UUID id) {
        return ProductResponse.from(productService.cancel(id));
    }
}
