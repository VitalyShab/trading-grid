package com.vitalys.trading_grid.service;

import com.vitalys.trading_grid.gateway.MarketGateway;
import com.vitalys.trading_grid.gateway.dto.OrderResponse;
import com.vitalys.trading_grid.gateway.dto.PlaceOrderRequest;
import com.vitalys.trading_grid.model.Order;
import com.vitalys.trading_grid.model.OrderStatus;
import com.vitalys.trading_grid.model.OrderType;
import com.vitalys.trading_grid.model.TradingPair;
import com.vitalys.trading_grid.repository.OrderRepository;
import com.vitalys.trading_grid.repository.TradingPairRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final TradingPairRepository tradingPairRepository;

    @Transactional
    public Order createOrder(Order order) {
        Order saved = orderRepository.save(order);
        log.info("Created order id={} type={} status={} tradingPairId={}",
                saved.getId(), saved.getType(), saved.getStatus(),
                saved.getTradingPair().getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Order> getOrdersByTradingPair(Long tradingPairId) {
        log.debug("Fetching orders for tradingPairId={}", tradingPairId);
        return orderRepository.findByTradingPairId(tradingPairId);
    }

    @Transactional(readOnly = true)
    public List<Order> getOrdersByStatus(OrderStatus status) {
        log.debug("Fetching orders with status={}", status);
        return orderRepository.findByStatus(status);
    }

    @Transactional
    public void cancelOrder(Order order, MarketGateway gateway) {
        order.setStatus(OrderStatus.CLOSE);
        gateway.cancelOrder(order.getTradingPair().getSymbol(), order.getMarketOrderId());
        orderRepository.save(order);
        log.info("Cancelled order id={} type={} tradingPairId={}",
                order.getId(), order.getType(), order.getTradingPair().getId());
    }

    @Transactional
    public void completeOrder(Order order) {
        order.setStatus(OrderStatus.COMPLETE);
        orderRepository.save(order);
        log.info("Complete order id={} type={} tradingPairId={}",
                order.getId(), order.getType(), order.getTradingPair().getId());
    }

    @Transactional
    public void fillOrder(Order order) {
        order.setStatus(OrderStatus.FILL);
        orderRepository.save(order);
        log.info("Filled order id={} type={} tradingPairId={}",
                order.getId(), order.getType(), order.getTradingPair().getId());
    }

    @Transactional
    public void proceedSellComplete(Order order, TradingPair tradingPair, MarketGateway gateway) {
        var tradingPairId = tradingPair.getId();
        List<Order> filledBuys = orderRepository.findByTradingPairIdAndStatusAndType(
                tradingPairId, OrderStatus.FILL, OrderType.BUY);
        for (Order filledOrder : filledBuys) {
            completeOrder(filledOrder);
            log.info("Completed buy order id={} tradingPairId={}", filledOrder.getId(), tradingPairId);
        }

        cancelAllOpenBuyOrders(gateway, tradingPair);

        completeOrder(order);
        tradingPair.setWallet(tradingPair.getWallet().add(
                order.getQty().multiply(order.getPrice())
        ));
    }

    @Transactional
    public void cancelAllOpenBuyOrders(MarketGateway gateway, TradingPair tradingPair) {
        var tradingPairId = tradingPair.getId();
        List<Order> openBuys = orderRepository.findByTradingPairIdAndStatusAndType(
                tradingPairId, OrderStatus.OPEN, OrderType.BUY);
        for (Order openOrder : openBuys) {
            cancelOrder(openOrder, gateway);
            tradingPair.setWallet(tradingPair.getWallet().add(
                    openOrder.getQty().multiply(openOrder.getPrice())
            ));
            log.info("Closed open buy order id={} tradingPairId={}", openOrder.getId(), tradingPairId);
        }
        tradingPairRepository.save(tradingPair);
    }

    @Transactional
    public @NonNull OrderResponse placeBuyOrder(TradingPair tradingPair,
                                                MarketGateway gateway,
                                                BigDecimal levelPrice,
                                                BigDecimal qty) {
        OrderResponse response = gateway.placeOrder(PlaceOrderRequest.builder()
                .symbol(tradingPair.getSymbol())
                .side(PlaceOrderRequest.OrderSide.BUY)
                .price(levelPrice)
                .quantity(qty)
                .build());

        createOrder(Order.builder()
                .tradingPair(tradingPair)
                .marketOrderId(response.getOrderId())
                .type(OrderType.BUY)
                .price(levelPrice)
                .qty(qty)
                .status(OrderStatus.OPEN)
                .build());

        tradingPair.setWallet(tradingPair.getWallet().subtract(tradingPair.getSpendPerOrder()));

        return response;
    }

    @Transactional
    public void handleStuckOrders(TradingPair tradingPair) {
        List<Order> fillBuyOrders = orderRepository.findByTradingPairIdAndStatusAndType(
                tradingPair.getId(), OrderStatus.FILL, OrderType.BUY);
        List<Order> openSellOrders = orderRepository.findByTradingPairIdAndStatusAndType(
                tradingPair.getId(), OrderStatus.OPEN, OrderType.SELL);
        fillBuyOrders.addAll(openSellOrders);
        if (!fillBuyOrders.isEmpty()) {
            for(Order fillBuyOrder : fillBuyOrders) {
                fillBuyOrder.setStatus(OrderStatus.STUCK);
                orderRepository.save(fillBuyOrder);
            }
        }
    }
}
