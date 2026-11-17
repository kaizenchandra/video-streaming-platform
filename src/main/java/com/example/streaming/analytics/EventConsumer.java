package com.example.streaming.analytics;

import com.example.streaming.shared.Db;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class EventConsumer {
    private final Db db;
    private final TransactionTemplate tx;

    public EventConsumer(Db d, TransactionTemplate t) {
        db = d;
        tx = t;
    }

    @Scheduled(fixedDelay = 5000)
    public void consume() {
        tx.executeWithoutResult(
                t -> {
                    for (var e :
                            db.sql.queryForList(
                                    "select * from events where processed_at is null order by created_at for update"
                                            + " skip locked limit 100")) {
                        if ("QualifiedView".equals(e.get("kind")))
                            db.sql.update(
                                    "insert into video_views(video_id,views) values(?,1) on conflict(video_id) do"
                                            + " update set views=video_views.views+1",
                                    e.get("aggregate_id"));
                        db.sql.update("update events set processed_at=now() where id=?", e.get("id"));
                    }
                });
    }
}
