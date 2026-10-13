package com.eh.bms.repository;

import com.eh.bms.model.BusinessProfile;
import org.hibernate.Session;

/** Database access for {@code business_profile}. */
public class BusinessProfileRepository {

    public long count(Session session) {
        return session.createSelectionQuery("select count(b) from BusinessProfile b", Long.class)
                .getSingleResult();
    }

    public void save(Session session, BusinessProfile profile) {
        session.persist(profile);
    }
}
