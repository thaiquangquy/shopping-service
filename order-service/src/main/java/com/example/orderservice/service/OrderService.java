package com.example.orderservice.service;

import com.example.orderservice.dto.InventoryResponse;
import com.example.orderservice.dto.OrderLineItemDto;
import com.example.orderservice.dto.OrderRequest;
import com.example.orderservice.model.Order;
import com.example.orderservice.model.OrderLineItems;
import com.example.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class OrderService {
    private final OrderRepository orderRepository;
    private final WebClient.Builder webClientBuilder;
    private final Tracer tracer;

    public String placeOrder(OrderRequest orderRequest) {
        Order order = Order.builder()
                .orderNumber(UUID.randomUUID().toString())
                .orderLineItemsList(orderRequest.getOrderLineItemDtoList()
                        .stream()
                        .map(this::mapToDto)
                        .toList())
                .build();

        List<String> skuCodes = order.getOrderLineItemsList().stream()
                .map(OrderLineItems::getSkuCode)
                .toList();

        Span intentoryServiceLookup = tracer.nextSpan().name("InventoryServiceLookup");
        try(Tracer.SpanInScope spanInScope = tracer.withSpan(intentoryServiceLookup.start())) {
            // Call inventory-service and place order if product is in stock
            // TODO: multi query param? use gRPC?
            InventoryResponse[] inventoryResponsesArray = webClientBuilder.build().get()
                    .uri("http://inventory-service/api/inventory",
                            uriBuilder -> uriBuilder.queryParam("skuCode", skuCodes).build())
                    .retrieve()
                    .bodyToMono(InventoryResponse[].class)
                    .block();

            boolean allProductsInStock = false;
            if (inventoryResponsesArray != null) {
                allProductsInStock = Arrays.stream(inventoryResponsesArray).allMatch(InventoryResponse::getIsInStock);
            }

            if (allProductsInStock){
                orderRepository.save(order);
                return "Order placed successfully";
            } else {
                // TODO: Add proper response
                throw new IllegalArgumentException("Product is not in stock, please try again later");
            }
        } finally {
            intentoryServiceLookup.end();
        }
    }

    // TODO: using mapstruct
    private OrderLineItems mapToDto(OrderLineItemDto orderLineItemDto) {
        return OrderLineItems.builder()
                .price(orderLineItemDto.getPrice())
                .quantity(orderLineItemDto.getQuantity())
                .skuCode(orderLineItemDto.getSkuCode())
                .build();
    }
}
