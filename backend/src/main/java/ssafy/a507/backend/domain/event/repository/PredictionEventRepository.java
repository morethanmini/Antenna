package ssafy.a507.backend.domain.event.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ssafy.a507.backend.domain.event.entity.PredictionEvent;

public interface PredictionEventRepository extends JpaRepository<PredictionEvent, Long> {}
