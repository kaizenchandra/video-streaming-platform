package com.example.streaming.identity;

import com.example.streaming.shared.Db;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class Identity {
    private final Db db;

    public Identity(Db db) {
        this.db = db;
    }

    public String user(Jwt jwt) {
        String id = jwt.getSubject();
        db.sql.update(
                "insert into accounts(id,display_name) values(?,?) on conflict do nothing", id, id);
        if (!Boolean.TRUE.equals(db.one("select active from accounts where id=?", id).get("active")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account deactivated");
        return id;
    }
}
