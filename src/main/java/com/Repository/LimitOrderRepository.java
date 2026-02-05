package com.Repository;

import com.Entity.LimitOrder;
import com.Entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.util.List;

public interface LimitOrderRepository extends JpaRepository<LimitOrder, Long> {
    List<LimitOrder> findAllByUserId(Long userId);
}
