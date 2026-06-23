package com.vitalys.trading_grid.repository;

import com.vitalys.trading_grid.model.Order;
import com.vitalys.trading_grid.model.OrderStatus;
import com.vitalys.trading_grid.model.OrderType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByTradingPairId(Long tradingPairId);

    List<Order> findByStatus(OrderStatus status);

    List<Order> findByTradingPairIdAndStatus(Long tradingPairId, OrderStatus status);

    List<Order> findByTradingPairIdAndTypeAndStatusIn(Long tradingPairId, OrderType type, List<OrderStatus> statuses);

    List<Order> findByTradingPairIdAndStatusAndType(Long tradingPairId, OrderStatus status, OrderType type);
}
