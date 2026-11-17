package io.github.trapaeureka.skyfeed.api.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * The oldest events not sent yet, locked for this transaction. Rows another instance is working on are
     * skipped, so two instances never send the same event at the same time.
     */
    @Query(nativeQuery = true, value = """
            select * from outbox_event
             where published_at is null
             order by created_at
             limit 50
               for update skip locked
            """)
    List<OutboxEvent> lockUnpublished();

    long countByPublishedAtIsNull();
}
