package com.eh.bms.repository;

import com.eh.bms.model.AppConfigEntry;
import org.hibernate.Session;

/** Database access for {@code app_config}. */
public class AppConfigRepository {

    public boolean existsByKey(Session session, String key) {
        return session.createSelectionQuery(
                        "select count(e) from AppConfigEntry e where e.configKey = :key", Long.class)
                .setParameter("key", key)
                .getSingleResult() > 0;
    }

    public void save(Session session, AppConfigEntry entry) {
        session.persist(entry);
    }
}
