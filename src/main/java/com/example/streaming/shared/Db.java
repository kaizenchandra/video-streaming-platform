package com.example.streaming.shared;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.*;

@Component
public class Db {
    public final JdbcTemplate sql;

    public Db(JdbcTemplate sql) {
        this.sql = sql;
    }

    public static void require(boolean condition, String reason) {
        if (!condition) throw new ResponseStatusException(CONFLICT, reason);
    }

    public Map<String, Object> one(String q, Object... a) {
        var rows = sql.queryForList(q, a);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Resource not found");
        return rows.getFirst();
    }

    public Map<String, Object> video(UUID id) {
        return one(
                "select v.* from videos v join accounts a on a.id=v.owner_id where v.id=? and not v.deleted"
                        + " and a.active",
                id);
    }

    public Map<String, Object> own(UUID id, String user) {
        var v = video(id);
        if (!user.equals(v.get("owner_id")))
            throw new ResponseStatusException(FORBIDDEN, "Owner required");
        return v;
    }

    public void readable(Map<String, Object> v, String user) {
        if (!user.equals(v.get("owner_id"))
                && (!Boolean.TRUE.equals(v.get("published"))
                || !"APPROVED".equals(v.get("moderation_status"))
                || "PRIVATE".equals(v.get("visibility"))))
            throw new ResponseStatusException(NOT_FOUND, "Resource not found");
    }

    public void event(UUID aggregate, String kind, String json) {
        sql.update(
                "insert into events(id,aggregate_id,kind,payload) values(?,?,?,?::jsonb)",
                UUID.randomUUID(),
                aggregate,
                kind,
                json);
    }
}
