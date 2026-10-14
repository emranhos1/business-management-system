package com.eh.bms.repository;

import com.eh.bms.model.AppConfigEntry;
import org.hibernate.Session;

import java.util.Optional;

/** Database access for {@code app_config}. */
public class AppConfigRepository {

    public boolean existsByKey(Session session, String key) {
        return session.createSelectionQuery(
                        "select count(e) from AppConfigEntry e where e.configKey = :key", Long.class)
                .setParameter("key", key)
                .getSingleResult() > 0;
    }

    public Optional<AppConfigEntry> findByKey(Session session, String key) {
        return session.createSelectionQuery("from AppConfigEntry e where e.configKey = :key", AppConfigEntry.class)
                .setParameter("key", key)
                .uniqueResultOptional();
    }

    public Optional<String> findValue(Session session, String key) {
        return session.createSelectionQuery(
                        "select e.configValue from AppConfigEntry e where e.configKey = :key", String.class)
                .setParameter("key", key)
                .uniqueResultOptional();
    }

    public void save(Session session, AppConfigEntry entry) {
        session.persist(entry);
    }
}
