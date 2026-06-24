package com.vitalys.trading_grid.service;

import com.vitalys.trading_grid.gateway.MarketGateway;
import com.vitalys.trading_grid.gateway.dto.Candle;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyEngine {
    private static final BigDecimal SPEND_INCREASE = new BigDecimal("1.05");
    private static final BigDecimal STEP_FRACTION = new BigDecimal("0.01");
    private static final BigDecimal STEP_GROWTH_FACTOR = new BigDecimal("1.23");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final int QTY_SCALE = 8;
    private static final int PRICE_SCALE = 8;

    private final TradingPairRepository tradingPairRepository;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final Map<String, MarketGateway> gateways;

    public void startStrategy() {
        tradingPairRepository.findAllByActiveTrueAndGatewayNot("backtest")
                .forEach(pair -> {
                    try {
                        execute(pair);
                    } catch (Exception e) {
                        log.error("Strategy tick failed for {}: {}", pair.getSymbol(), e.getMessage(), e);
                    }
                });
    }

    @Transactional
    public void execute(TradingPair pair) {
        MarketGateway gateway = gateways.get(pair.getGateway());
        if (gateway == null) {
            log.error("No MarketGateway bean registered for key '{}' (pair={})",
                    pair.getGateway(), pair.getSymbol());
            return;
        }

        var currentPrice = fetchCurrentPrice(gateway, pair.getSymbol());
        syncFilledOrders(pair, gateway, currentPrice);
        placeGridOrders(pair, gateway, currentPrice);

        tradingPairRepository.save(pair);
    }

    private void syncFilledOrders(TradingPair tradingPair, MarketGateway gateway, BigDecimal currentPrice) {
        List<Order> openOrders = orderRepository.findByTradingPairIdAndStatus(
                tradingPair.getId(), OrderStatus.OPEN);
        for (Order order : openOrders) {
            boolean filled = switch (order.getType()) {
                case BUY  -> currentPrice.compareTo(order.getPrice()) <= 0;
                case SELL -> currentPrice.compareTo(order.getPrice()) >= 0;
            };

            if (filled) {
                log.info("Order filled id={} type={} price={} currentPrice={} symbol={}",
                        order.getId(), order.getType(), order.getPrice(), currentPrice,
                        tradingPair.getSymbol());
                if (order.getType() == OrderType.SELL) {
                    orderService.proceedSellComplete(order, tradingPair, gateway);
                } else {
                    orderService.fillOrder(order);
                }
            }
        }

        placeSellOrder(tradingPair, gateway);
    }

    private void placeGridOrders(TradingPair tradingPair, MarketGateway gateway, BigDecimal currentPrice) {
        if (tradingPair.getOrderCount() <= 0
                || tradingPair.getSpendPerOrder() == null
                || tradingPair.getSpendPerOrder().compareTo(BigDecimal.ZERO) == 0) {
            log.warn("Skipping pair {} — orderCount={} spendPerOrder={}",
                    tradingPair.getSymbol(), tradingPair.getOrderCount(), tradingPair.getSpendPerOrder());
            return;
        }

        placeBuyOrders(tradingPair, gateway, currentPrice);
    }

    private void placeSellOrder(TradingPair tradingPair, MarketGateway gateway) {
        if (tradingPair.getProfitPercent() == null) {
            log.warn("Skipping sell order for {} — sellMarkupPercent is not configured", tradingPair.getSymbol());
            return;
        }

        List<Order> filledBuyOrders = orderRepository.findByTradingPairIdAndStatusAndType(
                tradingPair.getId(), OrderStatus.FILL, OrderType.BUY);
        if (filledBuyOrders.isEmpty()) {
            log.warn("No open FILL orders found for {} — skipping sell order placement", tradingPair.getSymbol());
            return;
        }

        List<Order> currentSellOrders = orderRepository.findByTradingPairIdAndStatusAndType(
                tradingPair.getId(), OrderStatus.OPEN, OrderType.SELL);

        BigDecimal totalQty = filledBuyOrders.stream()
                .map(Order::getQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(QTY_SCALE, RoundingMode.HALF_UP);
        BigDecimal totalSpent = filledBuyOrders.stream()
                .map((order) -> order.getPrice().multiply(order.getQty()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(QTY_SCALE, RoundingMode.HALF_UP);

        BigDecimal fee = tradingPair.getFeePercent() != null ? tradingPair.getFeePercent() : BigDecimal.ZERO;
        BigDecimal totalMarkup = tradingPair.getProfitPercent().add(fee.multiply(new BigDecimal("2")));
        BigDecimal sellPrice = totalSpent.divide(totalQty, QTY_SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.ONE.add(totalMarkup.divide(ONE_HUNDRED, QTY_SCALE, RoundingMode.HALF_UP)))
                .setScale(PRICE_SCALE, RoundingMode.HALF_UP);

        if (!currentSellOrders.isEmpty() && currentSellOrders.getFirst().getPrice().compareTo(sellPrice) == 0) {
            return;
        }

        cancelPreviousSellOrders(tradingPair, gateway);

        OrderResponse response = gateway.placeOrder(PlaceOrderRequest.builder()
                .symbol(tradingPair.getSymbol())
                .side(PlaceOrderRequest.OrderSide.SELL)
                .price(sellPrice)
                .quantity(totalQty)
                .build());

        orderService.createOrder(Order.builder()
                .tradingPair(tradingPair)
                .marketOrderId(response.getOrderId())
                .type(OrderType.SELL)
                .price(sellPrice)
                .qty(totalQty)
                .status(OrderStatus.OPEN)
                .build());

        log.info("Placed sell order for {} — price={} qty={} marketOrderId={}",
                tradingPair.getSymbol(), sellPrice, totalQty, response.getOrderId());
    }

    private void cancelPreviousSellOrders(TradingPair tradingPair, MarketGateway gateway) {
        List<Order> existingOpenSellOrders = orderRepository.findByTradingPairIdAndStatusAndType(
                tradingPair.getId(), OrderStatus.OPEN, OrderType.SELL);
        for (Order sellOrder : existingOpenSellOrders) {
            orderService.cancelOrder(sellOrder, gateway);
            log.info("Cancelled stale sell order id={} marketOrderId={} for {}",
                    sellOrder.getId(), sellOrder.getMarketOrderId(), tradingPair.getSymbol());
        }
    }

    private void placeBuyOrders(TradingPair tradingPair, MarketGateway gateway, BigDecimal currentPrice) {
        rebuildBuyOrdersCheck(tradingPair, gateway, currentPrice);

        if (orderService.isNotCompletedBuyOrdersExist(tradingPair)) {
            log.debug("No new buy orders needed for {} — orderCount={}",
                    tradingPair.getSymbol(), tradingPair.getOrderCount());
            return;
        }

        log.info("Placing {} grid orders for {} at base price {}", tradingPair.getOrderCount(),
                tradingPair.getSymbol(), currentPrice);

        //orderService.handleStuckOrders(tradingPair);

        BigDecimal currentStep = STEP_FRACTION;
        BigDecimal currentSpend = tradingPair.getWallet().multiply(SPEND_INCREASE.subtract(BigDecimal.ONE))
                        .divide(SPEND_INCREASE.pow(tradingPair.getOrderCount()).subtract(BigDecimal.ONE), QTY_SCALE, RoundingMode.HALF_DOWN);
        for (int i = 1; i <= tradingPair.getOrderCount(); i++) {
            if (tradingPair.getWallet().compareTo(currentSpend) < 0) {
                log.info("Not available fonds wallet={} for {}", tradingPair.getWallet(), tradingPair.getSymbol());
                break;
            }

            BigDecimal levelPrice = currentPrice.multiply(
                    BigDecimal.ONE.subtract(currentStep),
                    MathContext.DECIMAL128
            ).setScale(QTY_SCALE, RoundingMode.HALF_UP);

            currentStep = currentStep.multiply(STEP_GROWTH_FACTOR, MathContext.DECIMAL128);

            BigDecimal qty = currentSpend.divide(levelPrice, QTY_SCALE, RoundingMode.HALF_UP);

            OrderResponse response = orderService.placeBuyOrder(tradingPair, gateway, levelPrice, qty);

            currentSpend = currentSpend.multiply(SPEND_INCREASE);

            log.debug("Placed buy order level={} price={} qty={} marketOrderId={}",
                    i, levelPrice, qty, response.getOrderId());
        }
    }

    private void rebuildBuyOrdersCheck(TradingPair tradingPair, MarketGateway gateway, BigDecimal currentPrice) {
        List<Order> filledBuyOrders = orderRepository.findByTradingPairIdAndStatusAndType(
                tradingPair.getId(), OrderStatus.FILL, OrderType.BUY);
        if (!filledBuyOrders.isEmpty()) {
            return;
        }

        BigDecimal profitPercent = tradingPair.getProfitPercent();
        if (profitPercent == null) {
            return;
        }

        List<Order> openBuyOrders = orderRepository.findByTradingPairIdAndStatusAndType(
                tradingPair.getId(), OrderStatus.OPEN, OrderType.BUY);
        if (openBuyOrders.isEmpty()) {
            return;
        }

        BigDecimal highestBuyPrice = openBuyOrders.stream()
                .map(Order::getPrice)
                .max(BigDecimal::compareTo)
                .orElseThrow();

        BigDecimal percent = profitPercent.add(profitPercent.multiply(BigDecimal.TWO));
        BigDecimal multiplier = BigDecimal.ONE.add(
                percent.divide(ONE_HUNDRED, PRICE_SCALE, RoundingMode.HALF_UP));
        BigDecimal triggerPrice = highestBuyPrice
                .multiply(multiplier, MathContext.DECIMAL128)
                .setScale(PRICE_SCALE, RoundingMode.HALF_UP);

        if (currentPrice.compareTo(triggerPrice) > 0) {
            log.info("Price {} exceeds trigger {} (highest buy={}, profitPercent={}) for {} — no filled buy orders, closing all open buy orders",
                    currentPrice, triggerPrice, highestBuyPrice, profitPercent, tradingPair.getSymbol());
            orderService.cancelAllOpenBuyOrders(gateway, tradingPair);
        }
    }

    private BigDecimal fetchCurrentPrice(MarketGateway gateway, String symbol) {
        List<Candle> candles = gateway.getCandles(symbol, "1m", 1);
        if (candles == null || candles.isEmpty()) {
            throw new IllegalStateException("No candles returned for symbol " + symbol);
        }
        return candles.getLast().getClose();
    }
}
