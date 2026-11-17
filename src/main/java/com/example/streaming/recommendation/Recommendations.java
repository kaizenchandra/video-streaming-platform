package com.example.streaming.recommendation;

import com.example.streaming.shared.Db;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class Recommendations {
    private final Db db;

    public Recommendations(Db d) {
        db = d;
    }

    public List<Map<String, Object>> ranked(String user, int limit) {
        return db.sql.queryForList(
                "select v.id,v.title,v.category,(ln(1+coalesce(w.views,0))*0.3 + 0.4/(1+extract(epoch"
                        + " from(now()-v.created_at))/86400)+case when exists(select 1 from watch_progress p"
                        + " join videos seen on seen.id=p.video_id where p.user_id=? and"
                        + " seen.category=v.category) then 0.3 else 0 end) as score from videos v left join"
                        + " video_views w on w.video_id=v.id where v.published and not v.deleted and"
                        + " v.moderation_status='APPROVED' and exists(select 1 from accounts a where"
                        + " a.id=v.owner_id and a.active) and v.visibility='PUBLIC' order by score desc,v.id"
                        + " limit ?",
                user,
                Math.clamp(limit, 1, 100));
    }
}
