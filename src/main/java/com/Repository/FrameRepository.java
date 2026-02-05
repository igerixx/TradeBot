package com.Repository;

import com.Entity.Frame;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;

public interface FrameRepository extends JpaRepository<Frame, Long> {
    Frame findTop1ByOrderByIdDesc();
    List<Frame> findTop120ByOrderByIdDesc();
    List<Frame> findTop60ByOrderByIdDesc();
    List<Frame> findTop24ByOrderByIdDesc();
    List<Frame> findTop12ByOrderByIdDesc();
}
